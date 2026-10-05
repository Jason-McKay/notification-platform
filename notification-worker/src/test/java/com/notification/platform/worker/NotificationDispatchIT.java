package com.notification.platform.worker;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
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
 * The test that proves the skeleton walks: an event on a broker becomes an SMTP message and
 * a row in a real MySQL, through Flyway's schema and the JPA mapping validated against it.
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
    "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
    // One attempt, so a failing record reaches the DLT without waiting out the backoff.
    "notification.consumer.attempts=1",
    // GreenMail's conventional test port. The EMAIL sender talks real SMTP now, so the
    // skeleton needs something listening; 127.0.0.1 rather than localhost because that is
    // what GreenMail binds and localhost can resolve to ::1 first.
    "spring.mail.host=127.0.0.1",
    "spring.mail.port=3025"
})
@EmbeddedKafka(topics = {Topics.NOTIFICATION_REQUESTED, Topics.NOTIFICATION_REQUESTED_DLT}, partitions = 3)
@Testcontainers
class NotificationDispatchIT {

  @Container
  static final MySQLContainer MYSQL = new MySQLContainer("mysql:8")
      .withDatabaseName("notifications");

  /** The mail server the EMAIL sender delivers to: in this JVM, no container needed. */
  @RegisterExtension
  static final GreenMailExtension SMTP = new GreenMailExtension(ServerSetupTest.SMTP);

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
    registry.add("spring.datasource.username", MYSQL::getUsername);
    registry.add("spring.datasource.password", MYSQL::getPassword);
  }

  @Autowired
  private KafkaTemplate<String, Object> kafkaTemplate;

  @Autowired
  private NotificationRepository notifications;

  @Autowired
  private DeliveryAttemptRepository attempts;

  @Autowired
  private EmbeddedKafkaBroker broker;

  @Test
  void deliversAnEventAndRecordsTheAttempt() throws Exception {
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

    // And the message itself left the building, rendered from the configured template.
    assertThat(SMTP.waitForIncomingEmail(1)).isTrue();
    MimeMessage delivered = SMTP.getReceivedMessages()[0];
    assertThat(delivered.getAllRecipients()[0]).hasToString("jason@example.com");
    assertThat(delivered.getSubject()).isEqualTo("Your order A-1001 has shipped");
    assertThat(GreenMailUtil.getBody(delivered)).contains("Order A-1001 is on its way.");
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

  @Test
  void poisonRecordEndsUpOnTheDeadLetterTopic() {
    // A record the worker cannot deserialize at all. It fails before the listener runs, so
    // only ErrorHandlingDeserializer sees it -- and republishing it needs a producer that can
    // serialize the original bytes. Boot's default StringSerializer cannot, and the
    // dead-letter publication then fails silently in a loop that stalls the partition.
    KafkaTemplate<String, String> raw = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(
        Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString()),
        new StringSerializer(), new StringSerializer()));
    raw.send(Topics.NOTIFICATION_REQUESTED, "user-99", "this-is-not-json");

    Map<String, Object> props =
        KafkaTestUtils.consumerProps(broker.getBrokersAsString(), "dlt-it", "true");
    try (Consumer<String, String> dlt = new DefaultKafkaConsumerFactory<>(
        props, new StringDeserializer(), new StringDeserializer()).createConsumer()) {
      broker.consumeFromAnEmbeddedTopic(dlt, Topics.NOTIFICATION_REQUESTED_DLT);

      ConsumerRecords<String, String> records =
          KafkaTestUtils.getRecords(dlt, Duration.ofSeconds(30), 1);

      assertThat(records.records(Topics.NOTIFICATION_REQUESTED_DLT)).singleElement()
          .satisfies(record -> assertThat(record.value()).isEqualTo("this-is-not-json"));
    }
  }

  private static NotificationRequested event(UUID eventId) {
    return new NotificationRequested(eventId, "user-42", "jason@example.com",
        NotificationChannel.EMAIL, "order-shipped", Map.of("orderId", "A-1001"),
        Instant.parse("2026-08-27T21:30:00Z"));
  }
}
