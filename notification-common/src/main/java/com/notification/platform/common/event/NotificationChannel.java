package com.notification.platform.common.event;

/**
 * The delivery channels the platform knows about.
 *
 * <p>Part of the wire contract: the name is what travels on the topic, so renaming a
 * constant is a breaking change for anything already on the queue.
 */
public enum NotificationChannel {
  EMAIL,
  SMS,
  PUSH
}
