package com.notification.platform.api.service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;

import com.notification.platform.api.dto.NotificationRequest;
import com.notification.platform.api.dto.NotificationResponse;
import com.notification.platform.api.integration.cache.IdempotencyStore;
import com.notification.platform.api.integration.kafka.NotificationEventPublisher;
import com.notification.platform.common.event.NotificationChannel;
import com.notification.platform.common.event.NotificationRequested;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * The dedupe decisions. These are the ones worth testing: a retry that gets a new event id
 * after a publish that did land sends a customer two messages, and a key recorded for a
 * publish that failed swallows the client's retry.
 */
@ExtendWith(MockitoExtension.class)
class NotificationIngressServiceTest {

  private static final Instant NOW = Instant.parse("2026-08-27T21:30:00Z");
  private static final UUID DERIVED = EventIds.forIdempotencyKey("key-1");

  @Mock
  private IdempotencyStore idempotencyStore;

  @Mock
  private NotificationEventPublisher publisher;

  @Spy
  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

  @InjectMocks
  private NotificationIngressService service;

  private NotificationRequest request;

  @BeforeEach
  void setUp() {
    request = new NotificationRequest(
        "user-42", "jason@example.com", NotificationChannel.EMAIL, "order-shipped",
        Map.of("orderId", "A-1001"));
  }

  @Test
  void publishesUnderTheDerivedEventIdAndRecordsIt() {
    when(idempotencyStore.findPublished("key-1")).thenReturn(Optional.empty());
    when(idempotencyStore.recordPublished("key-1", DERIVED)).thenReturn(true);

    NotificationResponse response = service.accept(request, "key-1");

    ArgumentCaptor<NotificationRequested> published = ArgumentCaptor.forClass(NotificationRequested.class);
    verify(publisher).publish(published.capture());
    assertThat(published.getValue().eventId()).isEqualTo(DERIVED);
    assertThat(published.getValue().requestedAt()).isEqualTo(NOW);
    assertThat(published.getValue().partitionKey()).isEqualTo("user-42");
    assertThat(response.eventId()).isEqualTo(DERIVED);
    assertThat(response.duplicate()).isFalse();
  }

  @Test
  void returnsTheOriginalEventIdAndPublishesNothingOnAReplay() {
    UUID original = UUID.randomUUID();
    when(idempotencyStore.findPublished("key-1")).thenReturn(Optional.of(original));

    NotificationResponse response = service.accept(request, "key-1");

    assertThat(response.eventId()).isEqualTo(original);
    assertThat(response.duplicate()).isTrue();
    verifyNoInteractions(publisher);
    verify(idempotencyStore, never()).recordPublished(any(), any());
  }

  @Test
  void aRetryAfterAFailedPublishCarriesTheSameEventId() {
    // The first publish timed out, and may well have landed: the retry must be the same event
    // so the worker can tell the two copies apart from two notifications.
    when(idempotencyStore.findPublished("key-1")).thenReturn(Optional.empty());
    when(idempotencyStore.recordPublished("key-1", DERIVED)).thenReturn(true);
    doThrow(new IllegalStateException("broker did not acknowledge in time"))
        .doNothing()
        .when(publisher).publish(any());

    assertThatThrownBy(() -> service.accept(request, "key-1"))
        .isInstanceOf(IllegalStateException.class);
    NotificationResponse retry = service.accept(request, "key-1");

    ArgumentCaptor<NotificationRequested> published = ArgumentCaptor.forClass(NotificationRequested.class);
    verify(publisher, times(2)).publish(published.capture());
    assertThat(published.getAllValues())
        .extracting(NotificationRequested::eventId)
        .containsExactly(DERIVED, DERIVED);
    assertThat(retry.eventId()).isEqualTo(DERIVED);
    assertThat(retry.duplicate()).isFalse();
  }

  @Test
  void aFailedPublishLeavesNothingInRedisToSwallowTheRetry() {
    // Nothing is written before the ack, so there is nothing to release -- and so nothing a
    // Redis outage at this point could leave behind.
    when(idempotencyStore.findPublished("key-1")).thenReturn(Optional.empty());
    doThrow(new IllegalStateException("broker down")).when(publisher).publish(any());

    assertThatThrownBy(() -> service.accept(request, "key-1"))
        .isInstanceOf(IllegalStateException.class);

    verify(idempotencyStore).findPublished("key-1");
    verifyNoMoreInteractions(idempotencyStore);
  }

  @Test
  void aRedisFailureAfterTheAckStillAcceptsAndTheRetryIsNotSwallowed() {
    when(idempotencyStore.findPublished("key-1")).thenReturn(Optional.empty());
    when(idempotencyStore.recordPublished("key-1", DERIVED))
        .thenThrow(new RedisConnectionFailureException("redis down"))
        .thenReturn(true);
    doNothing().when(publisher).publish(any());

    NotificationResponse first = service.accept(request, "key-1");
    NotificationResponse retry = service.accept(request, "key-1");

    // The event is on the topic, so the first call is accepted. The retry finds no entry and
    // republishes rather than being swallowed -- under the same id, which the worker absorbs.
    assertThat(first.eventId()).isEqualTo(DERIVED);
    assertThat(first.duplicate()).isFalse();
    assertThat(retry.eventId()).isEqualTo(DERIVED);
    ArgumentCaptor<NotificationRequested> published = ArgumentCaptor.forClass(NotificationRequested.class);
    verify(publisher, times(2)).publish(published.capture());
    assertThat(published.getAllValues())
        .extracting(NotificationRequested::eventId)
        .containsExactly(DERIVED, DERIVED);
  }

  @Test
  void losingTheRecordRaceReadsAsADuplicate() {
    // A concurrent request under the same key published and recorded first. Both published
    // the same event id, so this one is a replay of it.
    when(idempotencyStore.findPublished("key-1")).thenReturn(Optional.empty());
    when(idempotencyStore.recordPublished(eq("key-1"), any())).thenReturn(false);

    NotificationResponse response = service.accept(request, "key-1");

    assertThat(response.eventId()).isEqualTo(DERIVED);
    assertThat(response.duplicate()).isTrue();
  }

  @Test
  void aRedisFailureBeforePublishingPublishesNothing() {
    when(idempotencyStore.findPublished("key-1"))
        .thenThrow(new RedisConnectionFailureException("redis down"));

    assertThatThrownBy(() -> service.accept(request, "key-1"))
        .isInstanceOf(RedisConnectionFailureException.class);

    verifyNoInteractions(publisher);
  }

  @Test
  void skipsDedupeEntirelyWithoutAnIdempotencyKey() {
    NotificationResponse response = service.accept(request, null);

    assertThat(response.duplicate()).isFalse();
    assertThat(response.eventId().version()).isEqualTo(4);
    verify(publisher).publish(any());
    verifyNoInteractions(idempotencyStore);
  }
}
