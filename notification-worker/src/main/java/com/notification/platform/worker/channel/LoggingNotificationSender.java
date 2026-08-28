package com.notification.platform.worker.channel;

import com.notification.platform.common.event.NotificationChannel;
import com.notification.platform.worker.domain.Notification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The stand-in sender: it logs instead of delivering.
 *
 * <p>One instance per channel is registered in {@code SenderConfig}, so the dispatcher's
 * channel lookup is exercised for real by the walking skeleton. Replacing any one of them
 * with a real provider means adding another {@link NotificationSender} bean for that
 * channel and dropping the stand-in.
 */
@RequiredArgsConstructor
@Slf4j
public class LoggingNotificationSender implements NotificationSender {

  private final NotificationChannel channel;

  @Override
  public NotificationChannel channel() {
    return channel;
  }

  @Override
  public void send(Notification notification) {
    log.info("[{}] delivering template {} to recipient {} (event {})",
        channel, notification.getTemplateKey(), notification.getRecipientId(),
        notification.getEventId());
  }
}
