package com.notification.platform.worker.channel;

import com.notification.platform.common.event.NotificationChannel;
import com.notification.platform.worker.domain.Notification;

/**
 * A way of actually delivering a notification.
 *
 * <p>Implementations are picked by channel at dispatch time, so adding SMS means adding a
 * bean, not editing the dispatcher. A sender that cannot deliver must throw: the retry and
 * dead-letter machinery is driven by exceptions, and a sender that swallows a failure
 * silently marks the notification SENT.
 */
public interface NotificationSender {

  NotificationChannel channel();

  void send(Notification notification);
}
