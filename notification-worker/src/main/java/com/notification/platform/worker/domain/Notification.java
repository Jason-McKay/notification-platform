package com.notification.platform.worker.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.notification.platform.common.event.NotificationChannel;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The delivery record for one notification.
 *
 * <p>The primary key is the event id from the topic, not a generated one. That is what
 * makes a Kafka redelivery harmless: the second insert of the same event collides with the
 * row already there instead of sending the notification twice.
 */
@Entity
@Table(name = "notification")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification {

  @Id
  @Column(name = "event_id", nullable = false, updatable = false)
  private UUID eventId;

  @Column(name = "recipient_id", nullable = false)
  private String recipientId;

  /** Where it goes, as opposed to who it is for. Set by the caller, opaque to the worker. */
  @Column(name = "destination", nullable = false)
  private String destination;

  @Enumerated(EnumType.STRING)
  @Column(name = "channel", nullable = false)
  private NotificationChannel channel;

  @Column(name = "template_key", nullable = false)
  private String templateKey;

  @Convert(converter = PayloadConverter.class)
  @Column(name = "payload", nullable = false)
  private Map<String, String> payload;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false)
  private DeliveryStatus status;

  @Column(name = "requested_at", nullable = false)
  private Instant requestedAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  public Notification(UUID eventId, String recipientId, String destination,
      NotificationChannel channel, String templateKey, Map<String, String> payload,
      Instant requestedAt, Instant updatedAt) {
    this.eventId = eventId;
    this.recipientId = recipientId;
    this.destination = destination;
    this.channel = channel;
    this.templateKey = templateKey;
    this.payload = payload;
    this.status = DeliveryStatus.PENDING;
    this.requestedAt = requestedAt;
    this.updatedAt = updatedAt;
  }

  public void markSent(Instant at) {
    this.status = DeliveryStatus.SENT;
    this.updatedAt = at;
  }

  public void markRetrying(Instant at) {
    this.status = DeliveryStatus.RETRYING;
    this.updatedAt = at;
  }

  public void markFailed(Instant at) {
    this.status = DeliveryStatus.FAILED;
    this.updatedAt = at;
  }

  public void markDeadLettered(Instant at) {
    this.status = DeliveryStatus.DEAD_LETTERED;
    this.updatedAt = at;
  }
}
