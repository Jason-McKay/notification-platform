package com.notification.platform.api.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import com.notification.platform.api.dto.NotificationRequest;
import com.notification.platform.api.dto.NotificationResponse;
import com.notification.platform.api.integration.cache.IdempotencyStore;
import com.notification.platform.api.integration.kafka.NotificationEventPublisher;
import com.notification.platform.common.event.NotificationRequested;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Validate, dedupe, publish. The whole of what this application does. */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationIngressService {

  private final IdempotencyStore idempotencyStore;
  private final NotificationEventPublisher publisher;
  private final Clock clock;

  /**
   * Accept a request for delivery.
   *
   * <p>{@code idempotencyKey} is optional: without it there is nothing stable to dedupe on
   * (two identical requests may genuinely be two notifications), so the request is simply
   * published under a random event id. Sending the header is how a client opts in to one
   * notification per key, however often it retries.
   *
   * <p>With a key, the event id is derived from it, and Redis hears about the key only once
   * the broker has acknowledged the event (ADR 0006). So every 202 means the event is on the
   * topic, and a failed publish leaves nothing behind to undo: the client's retry carries the
   * same event id, and if the first copy did reach the topic after all, the worker absorbs the
   * second.
   */
  public NotificationResponse accept(NotificationRequest request, String idempotencyKey) {
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      UUID eventId = UUID.randomUUID();
      publish(request, eventId);
      return new NotificationResponse(eventId, false);
    }

    // Redis being down here fails the request before anything is published. Going on would
    // be safe for delivery, but a replay would then be answered duplicate=false.
    Optional<UUID> alreadyPublished = idempotencyStore.findPublished(idempotencyKey);
    if (alreadyPublished.isPresent()) {
      log.debug("idempotency key {} replays event {}", idempotencyKey, alreadyPublished.get());
      return new NotificationResponse(alreadyPublished.get(), true);
    }

    UUID eventId = EventIds.forIdempotencyKey(idempotencyKey);
    // A throw is an honest 5xx with nothing to release: Redis has not been told anything.
    publish(request, eventId);
    return new NotificationResponse(eventId, !recordPublished(idempotencyKey, eventId));
  }

  /**
   * Record the key after the ack, and treat a Redis failure here as "first".
   *
   * <p>The event is on the topic, so failing the request would only make the client retry
   * something already accepted. Answering 202 instead costs only the replay flag: a later
   * retry finds no entry, republishes the same event id, and the worker absorbs the copy.
   */
  private boolean recordPublished(String idempotencyKey, UUID eventId) {
    try {
      return idempotencyStore.recordPublished(idempotencyKey, eventId);
    } catch (DataAccessException e) {
      log.warn("event {} published but idempotency key {} not recorded; a retry will republish"
          + " the same event id", eventId, idempotencyKey, e);
      return true;
    }
  }

  private void publish(NotificationRequest request, UUID eventId) {
    publisher.publish(new NotificationRequested(
        eventId,
        request.recipientId(),
        request.destination(),
        request.channel(),
        request.templateKey(),
        request.payload(),
        Instant.now(clock)));
  }
}
