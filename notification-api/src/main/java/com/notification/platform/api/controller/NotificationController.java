package com.notification.platform.api.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.notification.platform.api.dto.NotificationRequest;
import com.notification.platform.api.dto.NotificationResponse;
import com.notification.platform.api.service.NotificationIngressService;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * The whole public surface of this application: one write.
 *
 * <p>There is no {@code GET /api/notifications/{id}} here. The worker owns the delivery
 * records, so it serves the status read on :8081; adding a read here would mean two
 * services against one schema, which is the shape this platform exists to avoid.
 */
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

  private final NotificationIngressService ingressService;

  @Operation(summary = "Accept a notification request and publish it for delivery")
  @PostMapping
  public ResponseEntity<NotificationResponse> accept(
      @Valid @RequestBody NotificationRequest request,
      @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
    // 202, not 201: delivery has not happened yet and this service does not own a resource
    // the client could then GET from it.
    return ResponseEntity.accepted().body(ingressService.accept(request, idempotencyKey));
  }
}
