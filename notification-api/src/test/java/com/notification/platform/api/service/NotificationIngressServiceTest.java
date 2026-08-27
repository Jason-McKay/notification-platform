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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The dedupe decisions. These are the ones worth testing: an accepted duplicate that gets
 * published twice sends a customer two messages, and a claim left behind after a failed
 * publish swallows the client's retry.
 */
@ExtendWith(MockitoExtension.class)
class NotificationIngressServiceTest {

  private static final Instant NOW = Instant.parse("2026-08-27T21:30:00Z");

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
        "user-42", NotificationChannel.EMAIL, "order-shipped", Map.of("orderId", "A-1001"));
  }

  @Test
  void publishesWhenTheClaimIsWon() {
    when(idempotencyStore.claim(eq("key-1"), any())).thenReturn(Optional.empty());

    NotificationResponse response = service.accept(request, "key-1");

    ArgumentCaptor<NotificationRequested> published = ArgumentCaptor.forClass(NotificationRequested.class);
    verify(publisher).publish(published.capture());
    assertThat(published.getValue().eventId()).isEqualTo(response.eventId());
    assertThat(published.getValue().requestedAt()).isEqualTo(NOW);
    assertThat(published.getValue().partitionKey()).isEqualTo("user-42");
    assertThat(response.duplicate()).isFalse();
  }

  @Test
  void returnsTheOriginalEventIdAndPublishesNothingOnAReplay() {
    UUID original = UUID.randomUUID();
    when(idempotencyStore.claim(eq("key-1"), any())).thenReturn(Optional.of(original));

    NotificationResponse response = service.accept(request, "key-1");

    assertThat(response.eventId()).isEqualTo(original);
    assertThat(response.duplicate()).isTrue();
    verifyNoInteractions(publisher);
  }

  @Test
  void releasesTheClaimWhenPublishingFails() {
    when(idempotencyStore.claim(eq("key-1"), any())).thenReturn(Optional.empty());
    doThrow(new IllegalStateException("broker down")).when(publisher).publish(any());

    assertThatThrownBy(() -> service.accept(request, "key-1"))
        .isInstanceOf(IllegalStateException.class);

    verify(idempotencyStore).release("key-1");
  }

  @Test
  void skipsDedupeEntirelyWithoutAnIdempotencyKey() {
    NotificationResponse response = service.accept(request, null);

    assertThat(response.duplicate()).isFalse();
    verify(publisher).publish(any());
    verify(idempotencyStore, never()).claim(any(), any());
  }
}
