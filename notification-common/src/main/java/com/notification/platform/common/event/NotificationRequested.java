package com.notification.platform.common.event;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A request to send one notification, as published to {@link Topics#NOTIFICATION_REQUESTED}.
 *
 * <p>{@code eventId} is the idempotency anchor: the api derives it from the client's
 * {@code Idempotency-Key} when there is one, and the worker uses it to recognise a copy it
 * has seen, so neither a message replayed by Kafka nor one republished by a client's retry
 * sends twice. Opaque to the worker: how the api derives it is the api's business.
 *
 * @param eventId     one per notification: the same for every retry under one
 *                    Idempotency-Key, and across redeliveries
 * @param recipientId who the notification is for, opaque to this platform
 * @param destination where to deliver it -- an address, a number, a device token. Separate
 *                    from recipientId on purpose: that is who, this is where, and the two
 *                    stop being interchangeable the moment a recipient has two channels
 * @param channel     how to deliver it
 * @param templateKey which template the worker should render
 * @param payload     template variables; never null, may be empty
 * @param requestedAt when the api accepted the request
 */
public record NotificationRequested(
    UUID eventId,
    String recipientId,
    String destination,
    NotificationChannel channel,
    String templateKey,
    Map<String, String> payload,
    Instant requestedAt) {

  public NotificationRequested {
    Objects.requireNonNull(eventId, "eventId");
    Objects.requireNonNull(recipientId, "recipientId");
    Objects.requireNonNull(destination, "destination");
    Objects.requireNonNull(channel, "channel");
    Objects.requireNonNull(templateKey, "templateKey");
    Objects.requireNonNull(requestedAt, "requestedAt");
    payload = payload == null ? Map.of() : Map.copyOf(payload);
  }

  /**
   * The Kafka message key. Keying by recipient keeps one recipient's notifications on a
   * single partition, so they are delivered in the order they were accepted.
   */
  public String partitionKey() {
    return recipientId;
  }
}
