package com.notification.platform.worker.controller;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.notification.platform.common.event.NotificationChannel;
import com.notification.platform.common.security.Scopes;
import com.notification.platform.worker.domain.DeliveryAttempt;
import com.notification.platform.worker.domain.DeliveryStatus;
import com.notification.platform.worker.domain.Notification;
import com.notification.platform.worker.repository.DeliveryAttemptRepository;
import com.notification.platform.worker.repository.DeliveryAttemptRepository.TryCount;
import com.notification.platform.worker.repository.NotificationRepository;
import com.notification.platform.worker.repository.NotificationRepository.ChannelCount;
import com.notification.platform.worker.repository.NotificationRepository.StatusCount;
import com.notification.platform.worker.security.SecurityConfig;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP contract of the delivery reads, and who is allowed to ask for them.
 *
 * <p>The worker mints no tokens and serves no writes, so the only thing its chain has to
 * get right is that a read takes the read scope -- a token good enough to publish on the
 * api is not automatically good enough to read delivery records here.
 */
@WebMvcTest(NotificationStatusController.class)
@Import(SecurityConfig.class)
class NotificationStatusControllerTest {

  private static final UUID EVENT_ID = UUID.fromString("0f6bd3fc-6c1e-4f3e-9a1b-2b6f5a7c8d90");
  private static final Instant NOW = Instant.parse("2026-10-31T09:00:00Z");

  /** The slice loads no @Configuration of the app's own, so the controller's clock is this. */
  @TestConfiguration
  static class FixedClock {

    @Bean
    Clock clock() {
      return Clock.fixed(NOW, ZoneOffset.UTC);
    }
  }

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private NotificationRepository notifications;

  @MockitoBean
  private DeliveryAttemptRepository attempts;

  /** Replaces the real decoder the config declares; the jwt() post-processor never calls it. */
  @MockitoBean
  private JwtDecoder jwtDecoder;

  @Test
  void servesTheStatusToAReadScopedToken() throws Exception {
    Instant at = Instant.parse("2026-08-28T10:15:30Z");
    Notification notification = notification(at);
    notification.markSent(at);

    when(notifications.findById(EVENT_ID)).thenReturn(Optional.of(notification));
    when(attempts.findByEventIdOrderByAttemptedAtAsc(EVENT_ID)).thenReturn(
        List.of(new DeliveryAttempt(EVENT_ID, DeliveryStatus.SENT, "logged", at)));

    mockMvc.perform(get("/api/notifications/{eventId}", EVENT_ID)
            .with(tokenWith(Scopes.READ_AUTHORITY)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.eventId").value(EVENT_ID.toString()))
        .andExpect(jsonPath("$.recipientId").value("user-42"))
        .andExpect(jsonPath("$.destination").value("jason@example.com"))
        .andExpect(jsonPath("$.channel").value("EMAIL"))
        .andExpect(jsonPath("$.templateKey").value("order-shipped"))
        .andExpect(jsonPath("$.status").value("SENT"))
        .andExpect(jsonPath("$.payload").doesNotExist())
        .andExpect(jsonPath("$.attempts.length()").value(1))
        .andExpect(jsonPath("$.attempts[0].status").value("SENT"));
  }

  @Test
  void rejectsATokenWithoutTheReadScope() throws Exception {
    mockMvc.perform(get("/api/notifications/{eventId}", EVENT_ID)
            .with(tokenWith(Scopes.WRITE_AUTHORITY)))
        .andExpect(status().isForbidden());

    // The 403 has to come from the chain, before anything reads a delivery record.
    verifyNoInteractions(notifications);
    verifyNoInteractions(attempts);
  }

  @Test
  void rejectsAnUnauthenticatedRequest() throws Exception {
    mockMvc.perform(get("/api/notifications/{eventId}", EVENT_ID))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void unknownEventIdIsA404() throws Exception {
    when(notifications.findById(any(UUID.class))).thenReturn(Optional.empty());

    mockMvc.perform(get("/api/notifications/{eventId}", UUID.randomUUID())
            .with(tokenWith(Scopes.READ_AUTHORITY)))
        .andExpect(status().isNotFound());
  }

  @Test
  void listsAPageOfSummaries() throws Exception {
    Notification notification = notification(NOW);
    notification.markRetrying(NOW);
    PageRequest firstPage = PageRequest.of(0, 20, NotificationStatusController.NEWEST_FIRST);
    when(notifications.search(any(), any(), any(), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(notification), firstPage, 41));
    when(attempts.countTries(List.of(EVENT_ID))).thenReturn(List.of(tries(EVENT_ID, 2)));

    mockMvc.perform(get("/api/notifications").with(tokenWith(Scopes.READ_AUTHORITY)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].eventId").value(EVENT_ID.toString()))
        .andExpect(jsonPath("$.items[0].destination").value("jason@example.com"))
        .andExpect(jsonPath("$.items[0].channel").value("EMAIL"))
        .andExpect(jsonPath("$.items[0].templateKey").value("order-shipped"))
        .andExpect(jsonPath("$.items[0].status").value("RETRYING"))
        .andExpect(jsonPath("$.items[0].attemptCount").value(2))
        .andExpect(jsonPath("$.items[0].payload").doesNotExist())
        .andExpect(jsonPath("$.page").value(0))
        .andExpect(jsonPath("$.size").value(20))
        .andExpect(jsonPath("$.totalItems").value(41))
        .andExpect(jsonPath("$.totalPages").value(3));

    // No filters, the defaults, and the one sort the list allows.
    verify(notifications).search(null, null, null, firstPage);
  }

  @Test
  void passesTheFiltersAndPageThrough() throws Exception {
    when(notifications.search(any(), any(), any(), any(Pageable.class))).thenReturn(Page.empty());

    mockMvc.perform(get("/api/notifications")
            .param("status", "DEAD_LETTERED")
            .param("channel", "SMS")
            .param("recipientId", "user-42")
            .param("page", "2")
            .param("size", "10")
            .with(tokenWith(Scopes.READ_AUTHORITY)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(0));

    verify(notifications).search(DeliveryStatus.DEAD_LETTERED, NotificationChannel.SMS,
        "user-42", PageRequest.of(2, 10, NotificationStatusController.NEWEST_FIRST));
    // An empty page has nothing to count attempts for.
    verifyNoInteractions(attempts);
  }

  @Test
  void capsThePageSize() throws Exception {
    when(notifications.search(any(), any(), any(), any(Pageable.class))).thenReturn(Page.empty());

    mockMvc.perform(get("/api/notifications").param("size", "500")
            .with(tokenWith(Scopes.READ_AUTHORITY)))
        .andExpect(status().isOk());

    verify(notifications).search(null, null, null,
        PageRequest.of(0, 100, NotificationStatusController.NEWEST_FIRST));
  }

  @Test
  void treatsBlankFiltersAsAbsent() throws Exception {
    when(notifications.search(any(), any(), any(), any(Pageable.class))).thenReturn(Page.empty());

    mockMvc.perform(get("/api/notifications")
            .param("status", "")
            .param("channel", "")
            .param("recipientId", " ")
            .with(tokenWith(Scopes.READ_AUTHORITY)))
        .andExpect(status().isOk());

    verify(notifications).search(null, null, null,
        PageRequest.of(0, 20, NotificationStatusController.NEWEST_FIRST));
  }

  @ParameterizedTest
  @ValueSource(strings = {"page=-1", "size=0", "status=LOST", "channel=FAX"})
  void rejectsABadListParameter(String query) throws Exception {
    mockMvc.perform(get("/api/notifications?" + query).with(tokenWith(Scopes.READ_AUTHORITY)))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(notifications);
  }

  @Test
  void statsCountEveryStatusAndChannelIncludingZeros() throws Exception {
    Instant since = Instant.parse("2026-10-30T09:00:00Z");
    when(notifications.countByStatusSince(since)).thenReturn(List.of(
        statusCount(DeliveryStatus.SENT, 3), statusCount(DeliveryStatus.DEAD_LETTERED, 1)));
    when(notifications.countByChannelSince(since)).thenReturn(List.of(
        channelCount(NotificationChannel.EMAIL, 4)));

    mockMvc.perform(get("/api/notifications/stats").with(tokenWith(Scopes.READ_AUTHORITY)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.since").value("2026-10-30T09:00:00Z"))
        .andExpect(jsonPath("$.total").value(4))
        .andExpect(jsonPath("$.byStatus.PENDING").value(0))
        .andExpect(jsonPath("$.byStatus.SENT").value(3))
        .andExpect(jsonPath("$.byStatus.RETRYING").value(0))
        .andExpect(jsonPath("$.byStatus.FAILED").value(0))
        .andExpect(jsonPath("$.byStatus.DEAD_LETTERED").value(1))
        .andExpect(jsonPath("$.byChannel.EMAIL").value(4))
        .andExpect(jsonPath("$.byChannel.SMS").value(0))
        .andExpect(jsonPath("$.byChannel.PUSH").value(0));
  }

  @ParameterizedTest
  @ValueSource(strings = {"/api/notifications", "/api/notifications/stats"})
  void theListAndStatsTakeTheReadScope(String path) throws Exception {
    mockMvc.perform(get(path).with(tokenWith(Scopes.WRITE_AUTHORITY)))
        .andExpect(status().isForbidden());
    mockMvc.perform(get(path))
        .andExpect(status().isUnauthorized());

    verifyNoInteractions(notifications);
    verifyNoInteractions(attempts);
  }

  private static Notification notification(Instant at) {
    return new Notification(EVENT_ID, "user-42", "jason@example.com",
        NotificationChannel.EMAIL, "order-shipped", Map.of("orderId", "A-1001"), at, at);
  }

  private static TryCount tries(UUID eventId, long total) {
    return new TryCount() {

      @Override
      public UUID getEventId() {
        return eventId;
      }

      @Override
      public long getTotal() {
        return total;
      }
    };
  }

  private static StatusCount statusCount(DeliveryStatus status, long total) {
    return new StatusCount() {

      @Override
      public DeliveryStatus getStatus() {
        return status;
      }

      @Override
      public long getTotal() {
        return total;
      }
    };
  }

  private static ChannelCount channelCount(NotificationChannel channel, long total) {
    return new ChannelCount() {

      @Override
      public NotificationChannel getChannel() {
        return channel;
      }

      @Override
      public long getTotal() {
        return total;
      }
    };
  }

  private static RequestPostProcessor tokenWith(String authority) {
    return jwt().authorities(new SimpleGrantedAuthority(authority));
  }
}
