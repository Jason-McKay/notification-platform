package com.notification.platform.worker.service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.notification.platform.common.event.NotificationChannel;
import com.notification.platform.common.event.NotificationRequested;
import com.notification.platform.worker.channel.NotificationSender;
import com.notification.platform.worker.domain.Notification;
import com.notification.platform.worker.template.RenderedMessage;
import com.notification.platform.worker.template.TemplateRenderer;

import lombok.extern.slf4j.Slf4j;

/** Claims the delivery record, renders the copy, sends it, and records the result. */
@Service
@Slf4j
public class DispatchService {

  private final Map<NotificationChannel, NotificationSender> senders;
  private final DeliveryRecorder records;
  private final TemplateRenderer templates;

  public DispatchService(List<NotificationSender> senders, DeliveryRecorder records,
      TemplateRenderer templates) {
    this.senders = new EnumMap<>(NotificationChannel.class);
    senders.forEach(sender -> this.senders.put(sender.channel(), sender));
    this.records = records;
    this.templates = templates;
  }

  /**
   * Dispatch one event.
   *
   * <p>Deliberately <em>not</em> transactional -- see ADR 0004,
   * {@code docs/adr/0004-the-provider-call-leaves-the-transaction.md}. The claim commits,
   * the provider call happens with no transaction open, and the result is recorded in a
   * second one. A sender that takes ten seconds to time out is then
   * ten seconds of nothing, rather than ten seconds of a held row lock.
   *
   * <p>The window that buys is real and deliberate: a crash between the send and the record
   * leaves the row PENDING with the message already delivered, and the retry delivers it
   * again. At-least-once, stated rather than hidden.
   *
   * <p>Failures need nothing here. Anything thrown reaches the listener, which records the
   * attempt and lets the retry topics carry the event to the dead-letter topic.
   */
  public void dispatch(NotificationRequested event) {
    Optional<Notification> claimed = records.claim(event);
    if (claimed.isEmpty()) {
      log.debug("event {} already sent, ignoring redelivery", event.eventId());
      return;
    }
    Notification notification = claimed.get();

    NotificationSender sender = senders.get(event.channel());
    if (sender == null) {
      // Not retryable: another attempt cannot conjure a sender. Thrown anyway, so the
      // listener records the attempt, the retry policy exhausts, and the event lands on the
      // DLT where someone can see it.
      throw new IllegalStateException("no sender registered for channel " + event.channel());
    }

    RenderedMessage message =
        templates.render(notification.getTemplateKey(), notification.getPayload());

    sender.send(notification, message);

    records.recordSent(event.eventId());
  }
}
