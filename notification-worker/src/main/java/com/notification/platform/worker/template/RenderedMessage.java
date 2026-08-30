package com.notification.platform.worker.template;

/**
 * A template with its variables filled in: what a sender actually delivers.
 *
 * <p>Separate from {@code Notification} because the two answer different questions. The
 * notification is the delivery record -- who, which channel, what state. This is the copy,
 * and it is derived, which is why nothing persists it: re-rendering is cheap and a stored
 * copy would be one more thing that can disagree with the template it came from.
 *
 * @param subject rendered subject line
 * @param body    rendered body
 */
public record RenderedMessage(String subject, String body) {
}
