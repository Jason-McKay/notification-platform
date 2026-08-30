package com.notification.platform.worker.domain;

/** Where a notification has got to. */
public enum DeliveryStatus {
  /** Accepted from the topic, not yet handed to a sender. */
  PENDING,
  /** A sender took it. */
  SENT,
  /**
   * The last attempt failed. Set again on every failed attempt, so an event still working
   * its way through the retry topics reads FAILED between tries; it becomes SENT if a later
   * attempt succeeds, and stays FAILED once the event reaches the dead-letter topic.
   */
  FAILED
}
