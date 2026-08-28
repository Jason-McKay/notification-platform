package com.notification.platform.worker.controller;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
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
import com.notification.platform.worker.repository.NotificationRepository;
import com.notification.platform.worker.security.SecurityConfig;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP contract of the status read, and who is allowed to ask for it.
 *
 * <p>The worker mints no tokens and serves no writes, so the only thing its chain has to
 * get right is that a read takes the read scope -- a token good enough to publish on the
 * api is not automatically good enough to read delivery records here.
 */
@WebMvcTest(NotificationStatusController.class)
@Import(SecurityConfig.class)
class NotificationStatusControllerTest {

  private static final UUID EVENT_ID = UUID.fromString("0f6bd3fc-6c1e-4f3e-9a1b-2b6f5a7c8d90");

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
    Notification notification = new Notification(EVENT_ID, "user-42", NotificationChannel.EMAIL,
        "order-shipped", Map.of("orderId", "A-1001"), at, at);
    notification.markSent(at);

    when(notifications.findById(EVENT_ID)).thenReturn(Optional.of(notification));
    when(attempts.findByEventIdOrderByAttemptedAtAsc(EVENT_ID)).thenReturn(
        List.of(new DeliveryAttempt(EVENT_ID, DeliveryStatus.SENT, "logged", at)));

    mockMvc.perform(get("/api/notifications/{eventId}", EVENT_ID)
            .with(tokenWith(Scopes.READ_AUTHORITY)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.eventId").value(EVENT_ID.toString()))
        .andExpect(jsonPath("$.recipientId").value("user-42"))
        .andExpect(jsonPath("$.status").value("SENT"))
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

  private static RequestPostProcessor tokenWith(String authority) {
    return jwt().authorities(new SimpleGrantedAuthority(authority));
  }
}
