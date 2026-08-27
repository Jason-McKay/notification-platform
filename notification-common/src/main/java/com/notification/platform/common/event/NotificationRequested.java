package com.notification.platform.common.event;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A request to send one notification, as published to {@link Topics#NOTIFICATION_REQUESTED}.
 *
 * <p>{@code eventId} is the idempotency anchor: the api derives it once per accepted
 * request and the worker uses it to recognise a redelivery, so a message replayed by Kafka
 * does not send twice.
 *
 * @param eventId     unique per accepted request, stable across redeliveries
 * @param recipientId who the notification is for, opaque to this platform
 * @param channel     how to deliver it
 * @param templateKey which template the worker should render
 * @param payload     template variables; never null, may be empty
 * @param requestedAt when the api accepted the request
 */
public record NotificationRequested(
        UUID eventId,
        String recipientId,
        NotificationChannel channel,
        String templateKey,
        Map<String, String> payload,
        Instant requestedAt) {

    public NotificationRequested {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(recipientId, "recipientId");
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
