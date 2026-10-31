package com.notification.platform.worker.domain;

/**
 * Where a notification has got to, and what one attempt at it came to.
 *
 * <p>A notification moves PENDING, then RETRYING for every failed try that still has one
 * after it, then SENT if a try succeeds, or FAILED once the last one has not. FAILED is
 * therefore brief: the event is on its way to the dead-letter topic, and the DLT handler
 * moves it to DEAD_LETTERED when it arrives -- see ADR 0005.
 *
 * <p>An attempt row only ever holds SENT, FAILED or DEAD_LETTERED: one row per try, plus a
 * marker for the event being parked.
 *
 * <p>Stored as the constant's name in a {@code varchar(16)} on both tables, so a new value
 * longer than that needs both columns widened before it ships.
 */
public enum DeliveryStatus {
  /** Accepted from the topic, not yet handed to a sender. */
  PENDING,
  /** A sender took it. */
  SENT,
  /** A try failed and the retry topics still hold another one. */
  RETRYING,
  /** The last try failed. Settles to DEAD_LETTERED once the event reaches the DLT. */
  FAILED,
  /** On the dead-letter topic. Nothing will try it again without a redrive. */
  DEAD_LETTERED
}
