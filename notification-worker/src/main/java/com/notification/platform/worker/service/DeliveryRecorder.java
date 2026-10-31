package com.notification.platform.worker.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.notification.platform.common.event.NotificationRequested;
import com.notification.platform.common.event.Topics;
import com.notification.platform.worker.domain.DeliveryAttempt;
import com.notification.platform.worker.domain.DeliveryStatus;
import com.notification.platform.worker.domain.Notification;
import com.notification.platform.worker.repository.DeliveryAttemptRepository;
import com.notification.platform.worker.repository.NotificationRepository;

import lombok.RequiredArgsConstructor;

/**
 * Every write the worker makes, and the only place a transaction is opened.
 *
 * <p>They live here rather than on {@link DispatchService} because the provider call must
 * happen outside one -- see ADR 0004. The dispatcher orchestrates and is not transactional
 * at all; each method here opens and commits its own transaction, which is also why they
 * cannot simply be private methods on the dispatcher: a self-call does not go through the
 * proxy that makes {@code @Transactional} mean anything.
 *
 * <p>The recorded failure is the reason this class exists at all. A failure written inside
 * the delivery transaction rolls back with it, which left {@link DeliveryStatus#FAILED}
 * unreachable and the status read answering 404 for an event the api had accepted.
 */
@Service
@RequiredArgsConstructor
public class DeliveryRecorder {

  /** The detail column, so a long provider message cannot fail the insert. */
  private static final int MAX_DETAIL = 512;

  /** The detail on the attempt row that marks an event as parked. */
  static final String DEAD_LETTERED_DETAIL = "parked on " + Topics.NOTIFICATION_REQUESTED_DLT;

  private final NotificationRepository notifications;
  private final DeliveryAttemptRepository attempts;
  private final Clock clock;

  /**
   * Take ownership of an event before anything is delivered.
   *
   * <p>Redelivery is expected, not exceptional: Kafka guarantees at-least-once, so an event
   * whose row is already SENT is one the broker handed over twice.
   *
   * @return the row to deliver against, or empty if this event has already been sent
   */
  @Transactional
  public Optional<Notification> claim(NotificationRequested event) {
    Instant now = Instant.now(clock);

    Optional<Notification> existing = notifications.findById(event.eventId());
    if (existing.isPresent()) {
      return existing.get().getStatus() == DeliveryStatus.SENT ? Optional.empty() : existing;
    }

    return Optional.of(notifications.save(new Notification(event.eventId(), event.recipientId(),
        event.destination(), event.channel(), event.templateKey(), event.payload(),
        event.requestedAt(), now)));
  }

  /** Record a delivery the provider accepted. */
  @Transactional
  public void recordSent(UUID eventId) {
    Instant now = Instant.now(clock);

    Notification notification = notifications.findById(eventId).orElseThrow(
        () -> new IllegalStateException("no delivery record for event " + eventId));
    notification.markSent(now);
    attempts.save(new DeliveryAttempt(eventId, DeliveryStatus.SENT, null, now));
  }

  /**
   * Append a FAILED attempt for this event, creating the notification row if the claim
   * itself was what failed.
   *
   * @param retriesRemaining whether the retry topics still hold another try, which is what
   *     separates RETRYING from FAILED on the row; the attempt itself is FAILED either way
   */
  @Transactional
  public void recordFailedAttempt(NotificationRequested event, Throwable cause,
      boolean retriesRemaining) {
    Instant now = Instant.now(clock);

    Notification notification = findOrCreate(event, now);
    if (retriesRemaining) {
      notification.markRetrying(now);
    } else {
      notification.markFailed(now);
    }
    // delivery_attempt carries a foreign key to notification, so the row has to exist before
    // the attempt can point at it. Same transaction, notification first.
    notifications.save(notification);
    attempts.save(new DeliveryAttempt(event.eventId(), DeliveryStatus.FAILED, detail(cause), now));
  }

  /**
   * Record that the event reached the dead-letter topic, and say so in its attempt history.
   *
   * <p>Idempotent, because the DLT is consumed at-least-once like any other topic: a row
   * already DEAD_LETTERED is left alone. So is a SENT one -- a notification that went out
   * outranks a copy of its event that ended up parked.
   *
   * @return whether anything changed, so a redelivered DLT record is not counted twice
   */
  @Transactional
  public boolean recordDeadLettered(NotificationRequested event) {
    Instant now = Instant.now(clock);

    Notification notification = findOrCreate(event, now);
    if (notification.getStatus() == DeliveryStatus.SENT
        || notification.getStatus() == DeliveryStatus.DEAD_LETTERED) {
      return false;
    }
    notification.markDeadLettered(now);
    notifications.save(notification);
    attempts.save(new DeliveryAttempt(event.eventId(), DeliveryStatus.DEAD_LETTERED,
        DEAD_LETTERED_DETAIL, now));
    return true;
  }

  private Notification findOrCreate(NotificationRequested event, Instant now) {
    return notifications.findById(event.eventId())
        .orElseGet(() -> new Notification(event.eventId(), event.recipientId(),
            event.destination(), event.channel(), event.templateKey(), event.payload(),
            event.requestedAt(), now));
  }

  private static String detail(Throwable cause) {
    String message = cause.getMessage() != null ? cause.getMessage()
        : cause.getClass().getSimpleName();
    return message.length() <= MAX_DETAIL ? message : message.substring(0, MAX_DETAIL);
  }
}
