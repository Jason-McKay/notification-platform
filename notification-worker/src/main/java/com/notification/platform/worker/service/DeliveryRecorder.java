package com.notification.platform.worker.service;

import java.time.Clock;
import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.notification.platform.common.event.NotificationRequested;
import com.notification.platform.worker.domain.DeliveryAttempt;
import com.notification.platform.worker.domain.DeliveryStatus;
import com.notification.platform.worker.domain.Notification;
import com.notification.platform.worker.repository.DeliveryAttemptRepository;
import com.notification.platform.worker.repository.NotificationRepository;

import lombok.RequiredArgsConstructor;

/**
 * Records a failed delivery attempt in a transaction of its own.
 *
 * <p>It has to be separate from {@link DispatchService}: that method is transactional, so a
 * failure recorded inside it unwinds with everything else the attempt wrote and never
 * reaches the database. That left {@link DeliveryStatus#FAILED} unreachable and the status
 * read answering 404 for an event the api had accepted with 202.
 *
 * <p>Called from the listener rather than from the dispatcher, and deliberately so: by then
 * the dispatch transaction has already rolled back, so this one inserts the notification row
 * without waiting on a lock the transaction that just failed is still holding.
 */
@Service
@RequiredArgsConstructor
public class DeliveryRecorder {

  /** The detail column, so a long provider message cannot fail the insert. */
  private static final int MAX_DETAIL = 512;

  private final NotificationRepository notifications;
  private final DeliveryAttemptRepository attempts;
  private final Clock clock;

  /**
   * Append a FAILED attempt for this event, creating the notification row if the rolled-back
   * dispatch never committed one.
   */
  @Transactional
  public void recordFailedAttempt(NotificationRequested event, Throwable cause) {
    Instant now = Instant.now(clock);

    Notification notification = notifications.findById(event.eventId())
        .orElseGet(() -> new Notification(event.eventId(), event.recipientId(), event.channel(),
            event.templateKey(), event.payload(), event.requestedAt(), now));
    notification.markFailed(now);
    // delivery_attempt carries a foreign key to notification, so the row has to exist before
    // the attempt can point at it. Same transaction, notification first.
    notifications.save(notification);
    attempts.save(new DeliveryAttempt(event.eventId(), DeliveryStatus.FAILED, detail(cause), now));
  }

  private static String detail(Throwable cause) {
    String message = cause.getMessage() != null ? cause.getMessage()
        : cause.getClass().getSimpleName();
    return message.length() <= MAX_DETAIL ? message : message.substring(0, MAX_DETAIL);
  }
}
