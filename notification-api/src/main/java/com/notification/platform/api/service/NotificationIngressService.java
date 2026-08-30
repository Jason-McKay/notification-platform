package com.notification.platform.api.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

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
   * published. Sending the header is how a client opts in to at-most-once.
   */
  public NotificationResponse accept(NotificationRequest request, String idempotencyKey) {
    UUID eventId = UUID.randomUUID();

    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      publish(request, eventId);
      return new NotificationResponse(eventId, false);
    }

    Optional<UUID> alreadyAccepted = idempotencyStore.claim(idempotencyKey, eventId);
    if (alreadyAccepted.isPresent()) {
      log.debug("idempotency key {} replays event {}", idempotencyKey, alreadyAccepted.get());
      return new NotificationResponse(alreadyAccepted.get(), true);
    }

    try {
      publish(request, eventId);
    } catch (RuntimeException e) {
      // The claim outlives a failed publish only if we let it, and then the client's retry
      // would be answered "duplicate" for an event that was never sent.
      idempotencyStore.release(idempotencyKey);
      throw e;
    }
    return new NotificationResponse(eventId, false);
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
