package com.notification.platform.worker;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
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
import com.notification.platform.worker.channel.NotificationSender;
import com.notification.platform.worker.domain.DeliveryStatus;
import com.notification.platform.worker.domain.Notification;
import com.notification.platform.worker.repository.DeliveryAttemptRepository;
import com.notification.platform.worker.repository.NotificationRepository;
import com.notification.platform.worker.template.RenderedMessage;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The other half of the failure story: a record that deserializes fine and then fails
 * <em>in the listener</em>.
 *
 * <p>{@code NotificationDispatchIT} covers the record that cannot be deserialized at all.
 * That one never reaches the listener, so it goes straight to the DLT and no retry topic is
 * involved. A sender that throws takes the longer path -- one retry topic per backoff
 * interval, then the DLT -- and that is the path a real provider will take the first time
 * it times out.
 *
 * <p>Three attempts here rather than the one {@code NotificationDispatchIT} uses, because
 * the retry topics are the point: the backoff is 1s then 2s, so the sequence costs about
 * three seconds.
 */
@SpringBootTest(properties = {
    "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
    "notification.consumer.attempts=3",
    // The failing sender below replaces SenderConfig's bean of the same name. Contributing
    // a second SMS sender instead would leave DispatchService's EnumMap to keep whichever
    // of the two it saw last, which is not something to leave to bean ordering.
    "spring.main.allow-bean-definition-overriding=true"
})
// Spring Kafka creates the retry topics on demand at startup, but they still have to be
// declared here: consumeFromEmbeddedTopics validates against this list rather than against
// the broker, and rejects a topic that does exist with "not in embedded topic list".
// Spelled out rather than built from the constants below because an annotation cannot
// reference the fields of the class it sits on.
@EmbeddedKafka(topics = {Topics.NOTIFICATION_REQUESTED, "notification.requested-retry-1000",
    "notification.requested-retry-2000", Topics.NOTIFICATION_REQUESTED_DLT}, partitions = 1)
@Testcontainers
class NotificationRetryIT {

  /**
   * The retry topics are suffixed with the backoff <em>delay</em>, not with an attempt
   * index: the listener's backoff is 1s then doubling, so the names follow from it and
   * changing the backoff renames the topics.
   */
  private static final String RETRY_1S = Topics.NOTIFICATION_REQUESTED + "-retry-1000";
  private static final String RETRY_2S = Topics.NOTIFICATION_REQUESTED + "-retry-2000";

  @Container
  static final MySQLContainer MYSQL = new MySQLContainer("mysql:8")
      .withDatabaseName("notifications");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
    registry.add("spring.datasource.username", MYSQL::getUsername);
    registry.add("spring.datasource.password", MYSQL::getPassword);
  }

  /** An SMS sender that always fails, standing in for a provider that is down. */
  @TestConfiguration
  static class FailingSenderConfig {

    @Bean
    NotificationSender smsSender() {
      return new NotificationSender() {

        @Override
        public NotificationChannel channel() {
          return NotificationChannel.SMS;
        }

        @Override
        public void send(Notification notification, RenderedMessage message) {
          throw new IllegalStateException("sms provider is down");
        }
      };
    }
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
  void aSenderThatThrowsIsRetriedThenDeadLettered() {
    NotificationRequested event = event(UUID.randomUUID());

    Map<String, Object> props =
        KafkaTestUtils.consumerProps(broker.getBrokersAsString(), "retry-it", "true");
    try (Consumer<String, String> observer = new DefaultKafkaConsumerFactory<>(
        props, new StringDeserializer(), new StringDeserializer()).createConsumer()) {
      broker.consumeFromEmbeddedTopics(observer, RETRY_1S, RETRY_2S,
          Topics.NOTIFICATION_REQUESTED_DLT);

      kafkaTemplate.send(Topics.NOTIFICATION_REQUESTED, event.partitionKey(), event);

      // One record on each retry topic and one on the DLT: the whole sequence.
      ConsumerRecords<String, String> records =
          KafkaTestUtils.getRecords(observer, Duration.ofSeconds(30), 3);

      assertThat(records.records(RETRY_1S)).singleElement()
          .satisfies(record -> assertThat(record.value()).contains(event.eventId().toString()));
      assertThat(records.records(RETRY_2S)).singleElement()
          .satisfies(record -> assertThat(record.value()).contains(event.eventId().toString()));
      assertThat(records.records(Topics.NOTIFICATION_REQUESTED_DLT)).singleElement()
          .satisfies(record -> assertThat(record.value()).contains(event.eventId().toString()));
    }

    // And it left a trace the caller can see. DispatchService is transactional and the
    // sender throws inside it, so everything the attempt wrote rolls back; DeliveryRecorder
    // writing from the listener afterwards is the only reason there is a row here at all.
    // Without it the status read answers 404 for an event the api accepted with 202.
    assertThat(notifications.findById(event.eventId()))
        .get()
        .satisfies(record -> assertThat(record.getStatus()).isEqualTo(DeliveryStatus.FAILED));

    // One attempt per try, not just the last one.
    assertThat(attempts.findByEventIdOrderByAttemptedAtAsc(event.eventId()))
        .hasSize(3)
        .allSatisfy(attempt -> {
          assertThat(attempt.getStatus()).isEqualTo(DeliveryStatus.FAILED);
          assertThat(attempt.getDetail()).isEqualTo("sms provider is down");
        });
  }

  private static NotificationRequested event(UUID eventId) {
    return new NotificationRequested(eventId, "user-42", "+15550100",
        NotificationChannel.SMS, "order-shipped", Map.of("orderId", "A-1001"),
        Instant.parse("2026-08-27T21:30:00Z"));
  }
}
