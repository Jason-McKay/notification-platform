package com.notification.platform.api.dto;

import java.util.Map;

import com.notification.platform.common.event.NotificationChannel;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * What a client posts. Deliberately not the same type as the event on the topic: the
 * request is validated input, the event is a contract, and letting one be the other means
 * every API tweak becomes a wire-format change.
 */
public record NotificationRequest(
    @NotBlank String recipientId,
    @NotBlank String destination,
    @NotNull NotificationChannel channel,
    @NotBlank String templateKey,
    Map<String, String> payload) {
}
