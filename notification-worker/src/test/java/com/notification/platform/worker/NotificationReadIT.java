package com.notification.platform.worker;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.notification.platform.common.event.NotificationChannel;
import com.notification.platform.common.event.Topics;
import com.notification.platform.common.security.Scopes;
import com.notification.platform.worker.domain.DeliveryAttempt;
import com.notification.platform.worker.domain.DeliveryStatus;
import com.notification.platform.worker.domain.Notification;
import com.notification.platform.worker.repository.DeliveryAttemptRepository;
import com.notification.platform.worker.repository.NotificationRepository;

import static org.hamcrest.Matchers.contains;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The delivery list and stats against the real schema: the JPQL filters, the sort, the
 * paging and the grouped counts are all things a mocked repository cannot get wrong.
 *
 * <p>The rows are seeded through the repositories rather than sent through the broker, so
 * each one can be given the status and the update time the assertion needs. Kafka is still
 * the embedded broker, only because the worker's listener has to have something to connect
 * to while the context is up.
 */
@SpringBootTest(properties = "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}")
@AutoConfigureMockMvc
@EmbeddedKafka(topics = {Topics.NOTIFICATION_REQUESTED, Topics.NOTIFICATION_REQUESTED_DLT}, partitions = 3)
@Testcontainers
class NotificationReadIT {

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
  private MockMvc mockMvc;

  @Autowired
  private NotificationRepository notifications;

  @Autowired
  private DeliveryAttemptRepository attempts;

  @Autowired
  private Clock clock;

  // Newest first: retrying, deadLettered, sent, then stale, which is outside the stats window.
  private UUID retrying;
  private UUID deadLettered;
  private UUID sent;
  private UUID stale;

  @BeforeEach
  void seed() {
    // Attempts first: each one references the notification it belongs to.
    attempts.deleteAll();
    notifications.deleteAll();

    Instant now = Instant.now(clock);
    sent = seed("user-42", NotificationChannel.EMAIL, DeliveryStatus.SENT,
        now.minus(Duration.ofMinutes(10)), DeliveryStatus.SENT);
    deadLettered = seed("user-42", NotificationChannel.SMS, DeliveryStatus.DEAD_LETTERED,
        now.minus(Duration.ofMinutes(5)), DeliveryStatus.FAILED, DeliveryStatus.FAILED,
        DeliveryStatus.FAILED, DeliveryStatus.DEAD_LETTERED);
    retrying = seed("user-7", NotificationChannel.EMAIL, DeliveryStatus.RETRYING,
        now.minus(Duration.ofMinutes(1)), DeliveryStatus.FAILED);
    stale = seed("user-7", NotificationChannel.PUSH, DeliveryStatus.SENT,
        now.minus(Duration.ofHours(25)), DeliveryStatus.SENT);
  }

  @Test
  void listsNewestFirstWithTheTriesCounted() throws Exception {
    read("/api/notifications")
        .andExpect(jsonPath("$.items[*].eventId")
            .value(contains(ids(retrying, deadLettered, sent, stale))))
        // The DEAD_LETTERED marker is not a try: three failures read as three.
        .andExpect(jsonPath("$.items[*].attemptCount").value(contains(1, 3, 1, 1)))
        .andExpect(jsonPath("$.items[0].status").value("RETRYING"))
        .andExpect(jsonPath("$.items[0].payload").doesNotExist())
        .andExpect(jsonPath("$.page").value(0))
        .andExpect(jsonPath("$.size").value(20))
        .andExpect(jsonPath("$.totalItems").value(4))
        .andExpect(jsonPath("$.totalPages").value(1));
  }

  @Test
  void filtersAloneAndCombined() throws Exception {
    read("/api/notifications?status=SENT")
        .andExpect(jsonPath("$.items[*].eventId").value(contains(ids(sent, stale))));
    read("/api/notifications?channel=EMAIL")
        .andExpect(jsonPath("$.items[*].eventId").value(contains(ids(retrying, sent))));
    read("/api/notifications?recipientId=user-42")
        .andExpect(jsonPath("$.items[*].eventId").value(contains(ids(deadLettered, sent))));
    read("/api/notifications?channel=EMAIL&recipientId=user-42")
        .andExpect(jsonPath("$.items[*].eventId").value(contains(ids(sent))));
    read("/api/notifications?status=DEAD_LETTERED&channel=EMAIL")
        .andExpect(jsonPath("$.items.length()").value(0))
        .andExpect(jsonPath("$.totalItems").value(0));
  }

  @Test
  void pagesAndCapsTheSize() throws Exception {
    read("/api/notifications?size=2&page=1")
        .andExpect(jsonPath("$.items[*].eventId").value(contains(ids(sent, stale))))
        .andExpect(jsonPath("$.page").value(1))
        .andExpect(jsonPath("$.size").value(2))
        .andExpect(jsonPath("$.totalItems").value(4))
        .andExpect(jsonPath("$.totalPages").value(2));

    read("/api/notifications?size=500")
        .andExpect(jsonPath("$.size").value(100))
        .andExpect(jsonPath("$.items.length()").value(4));
  }

  @Test
  void statsCountTheLastDayOnly() throws Exception {
    read("/api/notifications/stats")
        .andExpect(jsonPath("$.total").value(3))
        .andExpect(jsonPath("$.byStatus.SENT").value(1))
        .andExpect(jsonPath("$.byStatus.RETRYING").value(1))
        .andExpect(jsonPath("$.byStatus.DEAD_LETTERED").value(1))
        .andExpect(jsonPath("$.byStatus.PENDING").value(0))
        .andExpect(jsonPath("$.byStatus.FAILED").value(0))
        .andExpect(jsonPath("$.byChannel.EMAIL").value(2))
        .andExpect(jsonPath("$.byChannel.SMS").value(1))
        .andExpect(jsonPath("$.byChannel.PUSH").value(0));
  }

  private ResultActions read(String uri) throws Exception {
    return mockMvc.perform(get(uri)
            .with(jwt().authorities(new SimpleGrantedAuthority(Scopes.READ_AUTHORITY))))
        .andExpect(status().isOk());
  }

  private UUID seed(String recipientId, NotificationChannel channel, DeliveryStatus status,
      Instant updatedAt, DeliveryStatus... tries) {
    UUID eventId = UUID.randomUUID();
    Notification notification = new Notification(eventId, recipientId, "jason@example.com",
        channel, "order-shipped", Map.of("orderId", "A-1001"), updatedAt.minusSeconds(5),
        updatedAt);
    notification.setStatus(status);
    notifications.save(notification);
    for (DeliveryStatus tried : tries) {
      attempts.save(new DeliveryAttempt(eventId, tried, tried.name().toLowerCase(), updatedAt));
    }
    return eventId;
  }

  private static String[] ids(UUID... eventIds) {
    String[] ids = new String[eventIds.length];
    for (int i = 0; i < eventIds.length; i++) {
      ids[i] = eventIds[i].toString();
    }
    return ids;
  }
}
