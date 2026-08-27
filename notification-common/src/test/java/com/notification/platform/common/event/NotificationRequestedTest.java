package com.notification.platform.common.event;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the wire format. This is the cheapest test in the repo and the one most likely to
 * catch a real break: both applications deserialize what the other wrote, so a renamed
 * component or a type Jackson cannot handle is a production failure, not a compile error.
 */
class NotificationRequestedTest {

  private final JsonMapper mapper = JsonMapper.builder().build();

  @Test
  void serializesAndDeserializesWithoutAnnotations() {
    NotificationRequested event = new NotificationRequested(
        UUID.fromString("0f6bd3fc-6c1e-4f3e-9a1b-2b6f5a7c8d90"),
        "user-42",
        NotificationChannel.EMAIL,
        "order-shipped",
        Map.of("orderId", "A-1001"),
        Instant.parse("2026-08-27T21:30:00Z"));

    String json = mapper.writeValueAsString(event);

    assertThat(json)
        .contains("\"eventId\":\"0f6bd3fc-6c1e-4f3e-9a1b-2b6f5a7c8d90\"")
        .contains("\"recipientId\":\"user-42\"")
        .contains("\"channel\":\"EMAIL\"")
        .contains("\"templateKey\":\"order-shipped\"")
        .contains("\"orderId\":\"A-1001\"");
    assertThat(mapper.readValue(json, NotificationRequested.class)).isEqualTo(event);
  }

  @Test
  void payloadDefaultsToEmptyAndIsCopied() {
    Map<String, String> mutable = new HashMap<>(Map.of("k", "v"));
    NotificationRequested withPayload = event(mutable);
    mutable.put("added", "after");

    assertThat(withPayload.payload()).containsExactly(Map.entry("k", "v"));
    assertThat(event(null).payload()).isEmpty();
  }

  @Test
  void rejectsMissingRequiredFields() {
    assertThatThrownBy(() -> new NotificationRequested(
        null, "user-42", NotificationChannel.SMS, "order-shipped", Map.of(), Instant.now()))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("eventId");
  }

  @Test
  void partitionKeyIsTheRecipient() {
    assertThat(event(Map.of()).partitionKey()).isEqualTo("user-42");
  }

  private static NotificationRequested event(Map<String, String> payload) {
    return new NotificationRequested(
        UUID.randomUUID(), "user-42", NotificationChannel.EMAIL, "order-shipped",
        payload, Instant.parse("2026-08-27T21:30:00Z"));
  }
}
