package com.notification.platform.api.integration.kafka;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.notification.platform.common.event.NotificationRequested;
import com.notification.platform.common.event.Topics;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Publishes accepted requests to {@link Topics#NOTIFICATION_REQUESTED}.
 *
 * <p><strong>The send is awaited on purpose.</strong> Fire-and-forget would let the api
 * answer 202 for a request the broker never took, and record its idempotency key as
 * published, so the client's retry would be answered as a duplicate of an event that does not
 * exist. Blocking for the ack costs a few milliseconds and turns a lost notification into an
 * honest 5xx.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationEventPublisher {

  private final KafkaTemplate<String, NotificationRequested> kafkaTemplate;

  @Value("${notification.publish.timeout:PT5S}")
  private Duration timeout;

  public void publish(NotificationRequested event) {
    try {
      kafkaTemplate.send(Topics.NOTIFICATION_REQUESTED, event.partitionKey(), event)
          .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
      log.debug("published {} for recipient {}", event.eventId(), event.recipientId());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new PublishFailedException(event, e);
    } catch (ExecutionException | TimeoutException e) {
      throw new PublishFailedException(event, e);
    }
  }

  /**
   * Thrown when the broker did not acknowledge the event within the timeout.
   *
   * <p>Not acknowledged is not the same as not written. A timed-out send is still being
   * retried inside the producer, for up to {@code delivery.timeout.ms}, and may land after
   * the caller has been told it failed. That is why a retry must carry the same event id
   * (ADR 0006).
   */
  public static class PublishFailedException extends RuntimeException {
    public PublishFailedException(NotificationRequested event, Throwable cause) {
      super("failed to publish notification event " + event.eventId(), cause);
    }
  }
}
