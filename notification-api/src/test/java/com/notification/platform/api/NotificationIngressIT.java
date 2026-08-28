package com.notification.platform.api;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.notification.platform.api.security.TokenService;
import com.notification.platform.common.event.Topics;

import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the ingress for real -- context, security, Redis and Kafka -- and checks that an
 * accepted request actually reaches the topic.
 *
 * <p>Worth its runtime because the unit tests mock the publisher, and the failure it covers
 * is a wiring failure: Boot's autoconfigured {@code KafkaTemplate<Object, Object>} does not
 * satisfy a template typed to the event, which no mocked test can see.
 *
 * <p>Needs Docker for Redis. Kafka is the embedded broker; see the note in
 * {@code NotificationDispatchIT} for why not Testcontainers.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}")
@EmbeddedKafka(topics = Topics.NOTIFICATION_REQUESTED, partitions = 1)
@Testcontainers
class NotificationIngressIT {

  private static final String BODY = """
      {"recipientId":"user-42","channel":"EMAIL","templateKey":"order-shipped",
       "payload":{"orderId":"A-1001"}}
      """;

  @Container
  static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
      .withExposedPorts(6379);

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
  }

  private static Consumer<String, String> consumer;

  private final HttpClient http = HttpClient.newHttpClient();
  private final JsonMapper json = JsonMapper.builder().build();

  @LocalServerPort
  private int port;

  @Autowired
  private TokenService tokenService;

  @Autowired
  private EmbeddedKafkaBroker broker;

  @AfterAll
  static void closeConsumer() {
    if (consumer != null) {
      consumer.close();
    }
  }

  @Test
  void acceptedRequestReachesTheTopic() throws Exception {
    subscribe();

    HttpResponse<String> response = post("it-key-001");
    Map<String, Object> body = json.readValue(response.body(), Map.class);

    assertThat(response.statusCode()).isEqualTo(202);
    assertThat(body).containsEntry("duplicate", false);

    ConsumerRecords<String, String> records =
        KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(20), 1);

    assertThat(records.records(Topics.NOTIFICATION_REQUESTED)).singleElement().satisfies(record -> {
      assertThat(record.key()).isEqualTo("user-42");
      assertThat(record.value())
          .contains("\"eventId\":\"" + body.get("eventId") + "\"")
          .contains("\"channel\":\"EMAIL\"");
      // No class name on the wire: the producer sends no type headers, by configuration.
      assertThat(record.headers().lastHeader("__TypeId__")).isNull();
    });
  }

  @Test
  void replayOfTheSameIdempotencyKeyIsNotPublishedTwice() throws Exception {
    subscribe();

    Map<String, Object> first = json.readValue(post("it-key-002").body(), Map.class);
    HttpResponse<String> replayResponse = post("it-key-002");
    Map<String, Object> replay = json.readValue(replayResponse.body(), Map.class);

    assertThat(replayResponse.statusCode()).isEqualTo(202);
    assertThat(replay).containsEntry("duplicate", true);
    assertThat(replay.get("eventId")).isEqualTo(first.get("eventId"));

    assertThat(KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(10), 1).count()).isEqualTo(1);
  }

  @Test
  void writeWithoutATokenIsRejected() throws Exception {
    HttpResponse<String> response = http.send(
        HttpRequest.newBuilder(URI.create(baseUrl() + "/api/notifications"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(BODY))
            .build(),
        HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(401);
  }

  private HttpResponse<String> post(String idempotencyKey) throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create(baseUrl() + "/api/notifications"))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + tokenService.issue("notification-client"))
            .header("Idempotency-Key", idempotencyKey)
            .POST(HttpRequest.BodyPublishers.ofString(BODY))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private String baseUrl() {
    return "http://localhost:" + port;
  }

  private void subscribe() {
    if (consumer == null) {
      Map<String, Object> props = KafkaTestUtils.consumerProps(
          broker.getBrokersAsString(), "notification-ingress-it", "true");
      consumer = new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), new StringDeserializer())
          .createConsumer();
      broker.consumeFromAnEmbeddedTopic(consumer, Topics.NOTIFICATION_REQUESTED);
    }
  }
}
