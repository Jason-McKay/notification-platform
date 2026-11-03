package com.notification.platform.api;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.notification.platform.api.integration.cache.IdempotencyStore;
import com.notification.platform.api.integration.kafka.NotificationEventPublisher;
import com.notification.platform.api.integration.kafka.NotificationEventPublisher.PublishFailedException;
import com.notification.platform.api.security.TokenService;
import com.notification.platform.api.service.EventIds;
import com.notification.platform.common.event.NotificationRequested;
import com.notification.platform.common.event.Topics;

import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

/**
 * Boots the ingress for real -- context, security, Redis and Kafka -- and checks that an
 * accepted request actually reaches the topic.
 *
 * <p>Worth its runtime because the unit tests mock the publisher, and the failure it covers
 * is a wiring failure: Boot's autoconfigured {@code KafkaTemplate<Object, Object>} does not
 * satisfy a template typed to the event, which no mocked test can see.
 *
 * <p>It also pins what reaches the topic when a publish or the Redis write fails part way:
 * the shapes the worker has to absorb (ADR 0006).
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
      {"recipientId":"user-42","destination":"jason@example.com","channel":"EMAIL",
       "templateKey":"order-shipped","payload":{"orderId":"A-1001"}}
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

  /** Real unless a test stubs it, and reset after each test, so the others see no change. */
  @MockitoSpyBean
  private NotificationEventPublisher publisher;

  @MockitoSpyBean
  private IdempotencyStore idempotencyStore;

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
  void retryAfterAnAckedButTimedOutPublishCarriesTheSameEventId() throws Exception {
    subscribe();
    // The first publish reaches the topic, then reports a timeout -- the producer's own
    // retries outlasting the api's wait. The client sees a 5xx and retries.
    doAnswer(invocation -> {
      invocation.callRealMethod();
      throw new PublishFailedException(invocation.getArgument(0, NotificationRequested.class),
          new TimeoutException("simulated: acked after the api stopped waiting"));
    }).doCallRealMethod().when(publisher).publish(any());
    UUID derived = EventIds.forIdempotencyKey("it-key-003");

    HttpResponse<String> failed = post("it-key-003");
    HttpResponse<String> retried = post("it-key-003");
    Map<String, Object> retry = json.readValue(retried.body(), Map.class);

    assertThat(failed.statusCode()).isBetween(500, 599);
    // Not swallowed as a duplicate, since nothing recorded the key for a publish that failed.
    assertThat(retried.statusCode()).isEqualTo(202);
    assertThat(retry)
        .containsEntry("eventId", derived.toString())
        .containsEntry("duplicate", false);

    // Two copies on the topic, both under the one event id: the worker's to absorb, which
    // NotificationDispatchIT proves it does. Before ADR 0006 these carried two random ids.
    ConsumerRecords<String, String> records =
        KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(20), 2);
    assertThat(records.records(Topics.NOTIFICATION_REQUESTED))
        .hasSize(2)
        .allSatisfy(record -> assertThat(record.value())
            .contains("\"eventId\":\"" + derived + "\""));
  }

  @Test
  void aRedisFailureAfterTheAckDoesNotSwallowTheRetry() throws Exception {
    subscribe();
    // Redis drops out between the ack and the write that would have recorded the key.
    doThrow(new RedisConnectionFailureException("simulated: redis down"))
        .doCallRealMethod()
        .when(idempotencyStore).recordPublished(any(), any());
    UUID derived = EventIds.forIdempotencyKey("it-key-004");

    HttpResponse<String> firstResponse = post("it-key-004");
    HttpResponse<String> retryResponse = post("it-key-004");
    Map<String, Object> first = json.readValue(firstResponse.body(), Map.class);
    Map<String, Object> retry = json.readValue(retryResponse.body(), Map.class);
    Map<String, Object> replay = json.readValue(post("it-key-004").body(), Map.class);

    // The event was on the topic, so the first call is accepted rather than failed.
    assertThat(firstResponse.statusCode()).isEqualTo(202);
    assertThat(first).containsEntry("eventId", derived.toString());
    // The retry republishes rather than being swallowed, under the same id, and records the
    // key this time -- so the replay after it is a duplicate, as before.
    assertThat(retryResponse.statusCode()).isEqualTo(202);
    assertThat(retry).containsEntry("eventId", derived.toString());
    assertThat(replay)
        .containsEntry("eventId", derived.toString())
        .containsEntry("duplicate", true);

    ConsumerRecords<String, String> records =
        KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(20), 2);
    assertThat(records.records(Topics.NOTIFICATION_REQUESTED))
        .hasSize(2)
        .allSatisfy(record -> assertThat(record.value())
            .contains("\"eventId\":\"" + derived + "\""));
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
