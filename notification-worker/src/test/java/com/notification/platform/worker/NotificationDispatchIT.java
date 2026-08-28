package com.notification.platform.worker;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.notification.platform.common.event.NotificationChannel;
import com.notification.platform.common.event.NotificationRequested;
import com.notification.platform.common.event.Topics;
import com.notification.platform.worker.domain.DeliveryStatus;
import com.notification.platform.worker.repository.DeliveryAttemptRepository;
import com.notification.platform.worker.repository.NotificationRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The test that proves the skeleton walks: an event on a broker becomes a row in a real
 * MySQL, through Flyway's schema and the JPA mapping validated against it.
 *
 * <p>MySQL is a container; Kafka is spring-kafka's embedded broker rather than a
 * Testcontainers one. Both Testcontainers Kafka flavours die on this Windows/Docker Desktop
 * setup -- the image's config step runs before Testcontainers can inject the advertised
 * listener, and Kafka refuses to advertise 0.0.0.0. The embedded broker is a real KRaft
 * broker in-JVM, so the listener, the deserializer and the retry topics are all genuinely
 * exercised; what it does not cover is the broker's own containerised configuration.
 *
 * <p>Needs Docker for MySQL. It is an {@code *IT}, so {@code ./mvnw test} skips it and
 * {@code ./mvnw verify} runs it under failsafe.
 */
@SpringBootTest(properties = {
    // The worker consumes; only this test produces, so the producer side is configured here
    // rather than in the application's own properties.
    "spring.kafka.producer.value-serializer=org.springframework.kafka.support.serializer.JacksonJsonSerializer",
    "spring.kafka.producer.properties.spring.json.add.type.headers=false",
    "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
    "notification.consumer.attempts=1"
})
@EmbeddedKafka(topics = Topics.NOTIFICATION_REQUESTED, partitions = 1)
@Testcontainers
class NotificationDispatchIT {

  @Container
  static final MySQLContainer MYSQL = new MySQLContainer("mysql:8")
      .withDatabaseName("notifications");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
    registry.add("spring.datasource.username", MYSQL::getUsername);
    registry.add("spring.datasource.password", MYSQL::getPassword);
  }

  @Autowired
  private KafkaTemplate<Object, Object> kafkaTemplate;

  @Autowired
  private NotificationRepository notifications;

  @Autowired
  private DeliveryAttemptRepository attempts;

  @Test
  void deliversAnEventAndRecordsTheAttempt() {
    NotificationRequested event = event(UUID.randomUUID());

    kafkaTemplate.send(Topics.NOTIFICATION_REQUESTED, event.partitionKey(), event);

    await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
        assertThat(notifications.findById(event.eventId()))
            .get()
            .satisfies(notification -> {
              assertThat(notification.getStatus()).isEqualTo(DeliveryStatus.SENT);
              assertThat(notification.getRecipientId()).isEqualTo("user-42");
              assertThat(notification.getPayload()).containsEntry("orderId", "A-1001");
            }));

    assertThat(attempts.findByEventIdOrderByAttemptedAtAsc(event.eventId()))
        .singleElement()
        .satisfies(attempt -> assertThat(attempt.getStatus()).isEqualTo(DeliveryStatus.SENT));
  }

  @Test
  void ignoresARedeliveryOfTheSameEvent() {
    NotificationRequested event = event(UUID.randomUUID());

    kafkaTemplate.send(Topics.NOTIFICATION_REQUESTED, event.partitionKey(), event);
    kafkaTemplate.send(Topics.NOTIFICATION_REQUESTED, event.partitionKey(), event);

    await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
        assertThat(notifications.findById(event.eventId())).isPresent());

    // The second delivery must not produce a second send. Give it room to have gone wrong
    // before asserting that it did not.
    await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(10)).untilAsserted(() ->
        assertThat(attempts.findByEventIdOrderByAttemptedAtAsc(event.eventId())).hasSize(1));
  }

  private static NotificationRequested event(UUID eventId) {
    return new NotificationRequested(eventId, "user-42", NotificationChannel.EMAIL,
        "order-shipped", Map.of("orderId", "A-1001"), Instant.parse("2026-08-27T21:30:00Z"));
  }
}
