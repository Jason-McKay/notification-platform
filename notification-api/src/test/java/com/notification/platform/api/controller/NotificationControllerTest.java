package com.notification.platform.api.controller;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.notification.platform.api.dto.NotificationResponse;
import com.notification.platform.api.security.SecurityConfig;
import com.notification.platform.api.service.NotificationIngressService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP contract of the ingress: what a client gets back, and that an unauthenticated
 * caller gets nothing at all.
 */
@WebMvcTest(NotificationController.class)
@Import(SecurityConfig.class)
class NotificationControllerTest {

  private static final String VALID_BODY = """
      {"recipientId":"user-42","channel":"EMAIL","templateKey":"order-shipped",
       "payload":{"orderId":"A-1001"}}
      """;

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private NotificationIngressService ingressService;

  /** SecurityConfig needs one; the jwt() post-processor means it is never called. */
  @MockitoBean
  private JwtDecoder jwtDecoder;

  @Test
  void acceptsAValidRequest() throws Exception {
    UUID eventId = UUID.fromString("0f6bd3fc-6c1e-4f3e-9a1b-2b6f5a7c8d90");
    when(ingressService.accept(any(), eq(null))).thenReturn(new NotificationResponse(eventId, false));

    mockMvc.perform(post("/api/notifications").with(jwt())
            .contentType(MediaType.APPLICATION_JSON)
            .content(VALID_BODY))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.eventId").value(eventId.toString()))
        .andExpect(jsonPath("$.duplicate").value(false));
  }

  @Test
  void passesTheIdempotencyKeyThrough() throws Exception {
    when(ingressService.accept(any(), eq("key-1")))
        .thenReturn(new NotificationResponse(UUID.randomUUID(), true));

    mockMvc.perform(post("/api/notifications").with(jwt())
            .header("Idempotency-Key", "key-1")
            .contentType(MediaType.APPLICATION_JSON)
            .content(VALID_BODY))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.duplicate").value(true));

    verify(ingressService).accept(any(), eq("key-1"));
  }

  @Test
  void rejectsAnUnauthenticatedRequest() throws Exception {
    mockMvc.perform(post("/api/notifications")
            .contentType(MediaType.APPLICATION_JSON)
            .content(VALID_BODY))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void rejectsAnIncompleteRequest() throws Exception {
    mockMvc.perform(post("/api/notifications").with(jwt())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"recipientId":"","channel":"EMAIL","templateKey":"order-shipped"}
                """))
        .andExpect(status().isBadRequest());
  }
}
