package com.notification.platform.api.dto;

import java.util.UUID;

/**
 * The receipt for an accepted request.
 *
 * @param eventId   the id to quote when asking the worker for delivery status
 * @param duplicate true when this request was recognised as a replay and nothing new was
 *                  published, so a client retrying a timeout can tell the two apart
 */
public record NotificationResponse(UUID eventId, boolean duplicate) {
}
