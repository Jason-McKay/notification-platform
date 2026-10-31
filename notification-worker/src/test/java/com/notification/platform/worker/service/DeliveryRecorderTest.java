package com.notification.platform.worker.service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import com.notification.platform.common.event.NotificationChannel;
import com.notification.platform.common.event.NotificationRequested;
import com.notification.platform.worker.domain.DeliveryAttempt;
import com.notification.platform.worker.domain.DeliveryStatus;
import com.notification.platform.worker.domain.Notification;
import com.notification.platform.worker.repository.DeliveryAttemptRepository;
import com.notification.platform.worker.repository.NotificationRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The status transitions a failure drives. The retry IT walks them end to end; these pin
 * the decisions themselves, including the two a happy path never reaches: a DLT record
 * redelivered, and a parked copy of an event that was in fact sent.
 */
@ExtendWith(MockitoExtension.class)
class DeliveryRecorderTest {

  private static final Instant NOW = Instant.parse("2026-10-31T09:00:00Z");
  private static final Instant EARLIER = Instant.parse("2026-10-31T08:59:00Z");
  private static final UUID EVENT_ID = UUID.fromString("0f6bd3fc-6c1e-4f3e-9a1b-2b6f5a7c8d90");

  @Mock
  private NotificationRepository notifications;

  @Mock
  private DeliveryAttemptRepository attempts;

  @Spy
  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

  @InjectMocks
  private DeliveryRecorder recorder;

  @Test
  void aFailureWithRetriesLeftReadsRetrying() {
    Notification row = row(DeliveryStatus.PENDING);
    when(notifications.findById(EVENT_ID)).thenReturn(Optional.of(row));

    recorder.recordFailedAttempt(event(), new IllegalStateException("provider timed out"), true);

    assertThat(row.getStatus()).isEqualTo(DeliveryStatus.RETRYING);
    assertThat(row.getUpdatedAt()).isEqualTo(NOW);
    assertThat(savedAttempt()).satisfies(attempt -> {
      assertThat(attempt.getStatus()).isEqualTo(DeliveryStatus.FAILED);
      assertThat(attempt.getDetail()).isEqualTo("provider timed out");
    });
  }

  @Test
  void theLastFailureReadsFailed() {
    Notification row = row(DeliveryStatus.RETRYING);
    when(notifications.findById(EVENT_ID)).thenReturn(Optional.of(row));

    recorder.recordFailedAttempt(event(), new IllegalStateException("provider timed out"), false);

    assertThat(row.getStatus()).isEqualTo(DeliveryStatus.FAILED);
    assertThat(savedAttempt().getStatus()).isEqualTo(DeliveryStatus.FAILED);
  }

  @Test
  void deadLetteringMarksTheRowAndSaysSoInTheHistory() {
    Notification row = row(DeliveryStatus.FAILED);
    when(notifications.findById(EVENT_ID)).thenReturn(Optional.of(row));

    assertThat(recorder.recordDeadLettered(event())).isTrue();

    assertThat(row.getStatus()).isEqualTo(DeliveryStatus.DEAD_LETTERED);
    assertThat(row.getUpdatedAt()).isEqualTo(NOW);
    assertThat(savedAttempt()).satisfies(attempt -> {
      assertThat(attempt.getStatus()).isEqualTo(DeliveryStatus.DEAD_LETTERED);
      assertThat(attempt.getDetail()).isEqualTo("parked on notification.requested-dlt");
    });
  }

  @Test
  void deadLetteringCreatesTheRowWhenNoTryLeftOne() {
    // A fatal exception skips the retry topics, and one thrown before the claim committed
    // leaves nothing behind; the DLT handler must still leave a record the caller can read.
    when(notifications.findById(EVENT_ID)).thenReturn(Optional.empty());

    assertThat(recorder.recordDeadLettered(event())).isTrue();

    ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
    verify(notifications).save(saved.capture());
    assertThat(saved.getValue().getEventId()).isEqualTo(EVENT_ID);
    assertThat(saved.getValue().getStatus()).isEqualTo(DeliveryStatus.DEAD_LETTERED);
  }

  @Test
  void aRedeliveredDltRecordChangesNothing() {
    when(notifications.findById(EVENT_ID)).thenReturn(Optional.of(row(DeliveryStatus.DEAD_LETTERED)));

    assertThat(recorder.recordDeadLettered(event())).isFalse();

    verify(attempts, never()).save(any());
  }

  @Test
  void aSentNotificationStaysSent() {
    Notification row = row(DeliveryStatus.SENT);
    when(notifications.findById(EVENT_ID)).thenReturn(Optional.of(row));

    assertThat(recorder.recordDeadLettered(event())).isFalse();

    assertThat(row.getStatus()).isEqualTo(DeliveryStatus.SENT);
    verify(attempts, never()).save(any());
  }

  private DeliveryAttempt savedAttempt() {
    ArgumentCaptor<DeliveryAttempt> saved = ArgumentCaptor.forClass(DeliveryAttempt.class);
    verify(attempts).save(saved.capture());
    return saved.getValue();
  }

  private static Notification row(DeliveryStatus status) {
    Notification row = new Notification(EVENT_ID, "user-42", "+15550100",
        NotificationChannel.SMS, "order-shipped", Map.of("orderId", "A-1001"), EARLIER, EARLIER);
    row.setStatus(status);
    return row;
  }

  private static NotificationRequested event() {
    return new NotificationRequested(EVENT_ID, "user-42", "+15550100",
        NotificationChannel.SMS, "order-shipped", Map.of("orderId", "A-1001"), EARLIER);
  }
}
