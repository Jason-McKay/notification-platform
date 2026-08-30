package com.notification.platform.worker.channel;

import com.notification.platform.common.event.NotificationChannel;
import com.notification.platform.worker.domain.Notification;
import com.notification.platform.worker.template.RenderedMessage;

/**
 * A way of actually delivering a notification.
 *
 * <p>Implementations are picked by channel at dispatch time, so adding SMS means adding a
 * bean, not editing the dispatcher. A sender that cannot deliver must throw: the retry and
 * dead-letter machinery is driven by exceptions, and a sender that swallows a failure
 * silently marks the notification SENT.
 *
 * <p>Senders receive the copy already rendered. Templating is one problem and delivery is
 * another, and keeping them apart is what lets the renderer stay a pure function while each
 * sender worries only about its provider.
 */
public interface NotificationSender {

  NotificationChannel channel();

  void send(Notification notification, RenderedMessage message);
}
