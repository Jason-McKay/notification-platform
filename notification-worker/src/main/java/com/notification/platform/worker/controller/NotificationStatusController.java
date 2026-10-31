package com.notification.platform.worker.controller;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.notification.platform.common.event.NotificationChannel;
import com.notification.platform.worker.domain.DeliveryStatus;
import com.notification.platform.worker.domain.Notification;
import com.notification.platform.worker.repository.DeliveryAttemptRepository;
import com.notification.platform.worker.repository.DeliveryAttemptRepository.TryCount;
import com.notification.platform.worker.repository.NotificationRepository;

import lombok.RequiredArgsConstructor;

/**
 * The delivery reads: one notification by id, a filtered page of them, and a summary.
 *
 * <p>They live on the worker because the worker owns the tables. Putting them on the api
 * would mean two services against one schema, which is exactly the coupling this platform
 * is shaped to avoid.
 *
 * <p>None of them exposes the payload -- see ADR 0005,
 * {@code docs/adr/0005-delivery-states-and-the-read-api.md}.
 */
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationStatusController {

  /** The largest page the list serves, whatever the client asks for. */
  static final int MAX_PAGE_SIZE = 100;

  /** How far back the stats look. */
  static final Duration STATS_WINDOW = Duration.ofHours(24);

  /**
   * Newest first, by the last change rather than the request, so a delivery that just
   * failed comes to the top. The event id breaks ties, so a page boundary never depends on
   * the order the database happens to return equal timestamps in.
   */
  static final Sort NEWEST_FIRST =
      Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.desc("eventId"));

  private final NotificationRepository notifications;
  private final DeliveryAttemptRepository attempts;
  private final Clock clock;

  public record AttemptView(DeliveryStatus status, String detail, Instant attemptedAt) {
  }

  public record StatusView(UUID eventId, String recipientId, String destination,
      NotificationChannel channel, String templateKey, DeliveryStatus status,
      Instant requestedAt, Instant updatedAt, List<AttemptView> attempts) {
  }

  /** One row of the list: fixed fields, no payload. */
  public record SummaryView(UUID eventId, String recipientId, String destination,
      NotificationChannel channel, String templateKey, DeliveryStatus status,
      Instant requestedAt, Instant updatedAt, long attemptCount) {
  }

  public record PageView(List<SummaryView> items, int page, int size, long totalItems,
      int totalPages) {
  }

  /** Every status and channel is present, zeros included. */
  public record StatsView(Instant since, long total, Map<DeliveryStatus, Long> byStatus,
      Map<NotificationChannel, Long> byChannel) {
  }

  @GetMapping
  public PageView list(
      @RequestParam(required = false) DeliveryStatus status,
      @RequestParam(required = false) NotificationChannel channel,
      @RequestParam(required = false) String recipientId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    if (page < 0 || size < 1) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "page must be 0 or more and size 1 or more");
    }

    Page<Notification> found = notifications.search(status, channel,
        recipientId == null || recipientId.isBlank() ? null : recipientId,
        PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE), NEWEST_FIRST));

    // One grouped query for the page rather than one per row.
    Map<UUID, Long> tries = found.isEmpty() ? Map.of()
        : attempts.countTries(found.map(Notification::getEventId).toList()).stream()
            .collect(Collectors.toMap(TryCount::getEventId, TryCount::getTotal));

    return new PageView(
        found.map(notification -> new SummaryView(
            notification.getEventId(),
            notification.getRecipientId(),
            notification.getDestination(),
            notification.getChannel(),
            notification.getTemplateKey(),
            notification.getStatus(),
            notification.getRequestedAt(),
            notification.getUpdatedAt(),
            tries.getOrDefault(notification.getEventId(), 0L))).toList(),
        found.getNumber(),
        found.getSize(),
        found.getTotalElements(),
        found.getTotalPages());
  }

  /** Counts over the rows updated in the last {@link #STATS_WINDOW}. */
  @GetMapping("/stats")
  public StatsView stats() {
    Instant since = Instant.now(clock).minus(STATS_WINDOW);

    Map<DeliveryStatus, Long> byStatus = zeroed(DeliveryStatus.class);
    notifications.countByStatusSince(since)
        .forEach(count -> byStatus.put(count.getStatus(), count.getTotal()));

    Map<NotificationChannel, Long> byChannel = zeroed(NotificationChannel.class);
    notifications.countByChannelSince(since)
        .forEach(count -> byChannel.put(count.getChannel(), count.getTotal()));

    long total = byStatus.values().stream().mapToLong(Long::longValue).sum();
    return new StatsView(since, total, byStatus, byChannel);
  }

  @GetMapping("/{eventId}")
  public ResponseEntity<StatusView> status(@PathVariable UUID eventId) {
    return notifications.findById(eventId)
        .map(notification -> ResponseEntity.ok(new StatusView(
            notification.getEventId(),
            notification.getRecipientId(),
            notification.getDestination(),
            notification.getChannel(),
            notification.getTemplateKey(),
            notification.getStatus(),
            notification.getRequestedAt(),
            notification.getUpdatedAt(),
            attempts.findByEventIdOrderByAttemptedAtAsc(eventId).stream()
                .map(a -> new AttemptView(a.getStatus(), a.getDetail(), a.getAttemptedAt()))
                .toList())))
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  private static <E extends Enum<E>> Map<E, Long> zeroed(Class<E> type) {
    Map<E, Long> counts = new EnumMap<>(type);
    for (E value : type.getEnumConstants()) {
      counts.put(value, 0L);
    }
    return counts;
  }
}
