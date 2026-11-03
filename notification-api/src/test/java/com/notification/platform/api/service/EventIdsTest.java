package com.notification.platform.api.service;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The derived event id. The service test covers what it is for; this pins that it is a real
 * version 5 UUID and that it cannot drift, because a drift between two deploys hands a
 * retrying client a new id for an event that may already be on the topic.
 */
class EventIdsTest {

  /** RFC 9562's DNS namespace, which the published test vectors are derived under. */
  private static final UUID DNS = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8");

  @Test
  void theSameKeyAlwaysGivesTheSameEventId() {
    assertThat(EventIds.forIdempotencyKey("order-A-1001-shipped"))
        .isEqualTo(EventIds.forIdempotencyKey("order-A-1001-shipped"));
  }

  @Test
  void differentKeysGiveDifferentEventIds() {
    assertThat(EventIds.forIdempotencyKey("order-A-1001-shipped"))
        .isNotEqualTo(EventIds.forIdempotencyKey("order-A-1002-shipped"))
        .isNotEqualTo(EventIds.forIdempotencyKey("order-A-1001-shipped "));
  }

  @Test
  void isANameBasedVersionFiveUuid() {
    UUID eventId = EventIds.forIdempotencyKey("order-A-1001-shipped");

    assertThat(eventId.version()).isEqualTo(5);
    assertThat(eventId.variant()).isEqualTo(2);
  }

  @Test
  void matchesThePublishedTestVectors() {
    // RFC 9562's version 5 example, and the one in Python's uuid documentation.
    assertThat(EventIds.v5(DNS, "www.example.com"))
        .isEqualTo(UUID.fromString("2ed6657d-e927-568b-95e1-2665a8aea6a2"));
    assertThat(EventIds.v5(DNS, "python.org"))
        .isEqualTo(UUID.fromString("886313e1-3b8a-5372-9b90-0c9aee199e5d"));
  }

  @Test
  void theNamespaceNeverChanges() {
    // Changing it re-keys every request: a client retrying across that deploy gets a new
    // event id, and the worker delivers the notification twice. Never update this literal.
    assertThat(EventIds.NAMESPACE)
        .isEqualTo(UUID.fromString("77e16988-ab8d-48d8-a824-7b2747f6e81e"));
  }

  @Test
  void derivedIdsNeverChange() {
    // Computed independently of this class. The non-ASCII key fails if the key's bytes ever
    // stop being UTF-8, which would re-key every such request just as a new namespace would.
    assertThat(EventIds.forIdempotencyKey("order-A-1001-shipped"))
        .isEqualTo(UUID.fromString("1a9de433-944a-5354-bce6-c653bbd57b57"));
    assertThat(EventIds.forIdempotencyKey("r\u00e9sum\u00e9-1"))
        .isEqualTo(UUID.fromString("e3e360ae-792b-5f34-b661-57424a8d14d0"));
  }
}
