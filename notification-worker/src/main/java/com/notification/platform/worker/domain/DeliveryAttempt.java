package com.notification.platform.worker.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One try at delivering one notification, kept even when it failed.
 *
 * <p>The attempt history is the difference between "this went out" and "this went out on
 * the third try after two timeouts", which is the question anyone debugging a notification
 * platform actually asks.
 */
@Entity
@Table(name = "delivery_attempt")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DeliveryAttempt {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "event_id", nullable = false, updatable = false)
  private UUID eventId;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false)
  private DeliveryStatus status;

  @Column(name = "detail", length = 512)
  private String detail;

  @Column(name = "attempted_at", nullable = false)
  private Instant attemptedAt;

  public DeliveryAttempt(UUID eventId, DeliveryStatus status, String detail, Instant attemptedAt) {
    this.eventId = eventId;
    this.status = status;
    this.detail = detail;
    this.attemptedAt = attemptedAt;
  }
}
