package com.notification.platform.worker.service;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.notification.platform.common.event.NotificationChannel;
import com.notification.platform.common.event.NotificationRequested;
import com.notification.platform.worker.channel.NotificationSender;
import com.notification.platform.worker.domain.DeliveryAttempt;
import com.notification.platform.worker.domain.DeliveryStatus;
import com.notification.platform.worker.domain.Notification;
import com.notification.platform.worker.repository.DeliveryAttemptRepository;
import com.notification.platform.worker.repository.NotificationRepository;
import com.notification.platform.worker.template.RenderedMessage;
import com.notification.platform.worker.template.TemplateRenderer;

import lombok.extern.slf4j.Slf4j;

/** Records the notification, hands it to the right sender, and records what happened. */
@Service
@Slf4j
public class DispatchService {

  private final NotificationRepository notifications;
  private final DeliveryAttemptRepository attempts;
  private final Map<NotificationChannel, NotificationSender> senders;
  private final TemplateRenderer templates;
  private final Clock clock;

  public DispatchService(NotificationRepository notifications, DeliveryAttemptRepository attempts,
      List<NotificationSender> senders, TemplateRenderer templates, Clock clock) {
    this.notifications = notifications;
    this.attempts = attempts;
    this.senders = new EnumMap<>(NotificationChannel.class);
    senders.forEach(sender -> this.senders.put(sender.channel(), sender));
    this.templates = templates;
    this.clock = clock;
  }

  /**
   * Dispatch one event.
   *
   * <p>Transactional with the send inside it on purpose: at this size, a sender that throws
   * should roll the half-written record back and let the retry topic bring the event round
   * again, rather than leave a PENDING row nothing will ever revisit. A real provider call
   * would be moved out of the transaction and reconciled instead -- worth an ADR when that
   * day comes.
   *
   * <p>Nothing here writes the failure down, and that is the consequence of the rollback:
   * anything this method records about a failed attempt unwinds with it. {@link
   * DeliveryRecorder}, called by the listener once this transaction is over, is what leaves
   * the trace.
   *
   * <p>Redelivery is expected, not exceptional: Kafka guarantees at-least-once, so this
   * checks for an existing SENT record before doing anything.
   */
  @Transactional
  public void dispatch(NotificationRequested event) {
    Instant now = Instant.now(clock);

    Optional<Notification> existing = notifications.findById(event.eventId());
    if (existing.isPresent() && existing.get().getStatus() == DeliveryStatus.SENT) {
      log.debug("event {} already sent, ignoring redelivery", event.eventId());
      return;
    }

    Notification notification = existing.orElseGet(() -> notifications.save(new Notification(
        event.eventId(), event.recipientId(), event.channel(), event.templateKey(),
        event.payload(), event.requestedAt(), now)));

    NotificationSender sender = senders.get(event.channel());
    if (sender == null) {
      // Not retryable: another attempt cannot conjure a sender. Thrown anyway, so the
      // listener records the attempt, the retry policy exhausts, and the event lands on the
      // DLT where someone can see it.
      throw new IllegalStateException("no sender registered for channel " + event.channel());
    }

    // Rendered before the send and inside the transaction: it is a pure function, and a
    // template that cannot be rendered is a failed delivery like any other -- recorded on
    // the attempt, retried, then parked on the DLT.
    RenderedMessage message =
        templates.render(notification.getTemplateKey(), notification.getPayload());

    sender.send(notification, message);
    notification.markSent(now);
    attempts.save(new DeliveryAttempt(event.eventId(), DeliveryStatus.SENT, null, now));
  }
}
