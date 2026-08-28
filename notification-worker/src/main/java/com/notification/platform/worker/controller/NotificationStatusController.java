package com.notification.platform.worker.controller;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.notification.platform.worker.domain.DeliveryStatus;
import com.notification.platform.worker.repository.DeliveryAttemptRepository;
import com.notification.platform.worker.repository.NotificationRepository;

import lombok.RequiredArgsConstructor;

/**
 * The delivery status read.
 *
 * <p>It lives on the worker because the worker owns the tables. Putting it on the api would
 * mean two services against one schema, which is exactly the coupling this platform is
 * shaped to avoid.
 */
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationStatusController {

  private final NotificationRepository notifications;
  private final DeliveryAttemptRepository attempts;

  public record AttemptView(DeliveryStatus status, String detail, Instant attemptedAt) {
  }

  public record StatusView(UUID eventId, String recipientId, DeliveryStatus status,
      Instant requestedAt, Instant updatedAt, List<AttemptView> attempts) {
  }

  @GetMapping("/{eventId}")
  public ResponseEntity<StatusView> status(@PathVariable UUID eventId) {
    return notifications.findById(eventId)
        .map(notification -> ResponseEntity.ok(new StatusView(
            notification.getEventId(),
            notification.getRecipientId(),
            notification.getStatus(),
            notification.getRequestedAt(),
            notification.getUpdatedAt(),
            attempts.findByEventIdOrderByAttemptedAtAsc(eventId).stream()
                .map(a -> new AttemptView(a.getStatus(), a.getDetail(), a.getAttemptedAt()))
                .toList())))
        .orElseGet(() -> ResponseEntity.notFound().build());
  }
}
