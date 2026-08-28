package com.notification.platform.worker.domain;

/** Where a notification has got to. */
public enum DeliveryStatus {
  /** Accepted from the topic, not yet handed to a sender. */
  PENDING,
  /** A sender took it. */
  SENT,
  /** Every attempt failed; the event is on its way to the dead-letter topic. */
  FAILED
}
