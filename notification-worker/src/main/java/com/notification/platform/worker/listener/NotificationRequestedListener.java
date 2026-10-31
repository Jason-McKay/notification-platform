package com.notification.platform.worker.listener;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.RetryTopicHeaders;
import org.springframework.kafka.retrytopic.SameIntervalTopicReuseStrategy;
import org.springframework.kafka.annotation.BackOff;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import com.notification.platform.common.event.NotificationRequested;
import com.notification.platform.common.event.Topics;
import com.notification.platform.worker.metrics.DeliveryMetrics;
import com.notification.platform.worker.service.DeliveryRecorder;
import com.notification.platform.worker.service.DispatchService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The consumer.
 *
 * <p>{@code @RetryableTopic} moves a failing event onto retry topics rather than blocking
 * the partition while it sleeps -- the reason to reach for it instead of an in-listener
 * retry: one poisonous recipient must not hold up everyone else's notifications. After the
 * configured attempts the event lands on {@link Topics#NOTIFICATION_REQUESTED_DLT}, where
 * {@link #onDeadLettered} records that it did.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationRequestedListener {

  private final DispatchService dispatchService;
  private final DeliveryRecorder recorder;
  private final DeliveryMetrics metrics;

  /**
   * The same property {@code @RetryableTopic} reads, so a failed try can tell whether the
   * retry topics will give the event another one.
   */
  @Value("${notification.consumer.attempts:3}")
  private int maxAttempts;

  @RetryableTopic(
      attempts = "${notification.consumer.attempts:3}",
      backOff = @BackOff(delay = 1000, multiplier = 2.0, maxDelay = 8000),
      sameIntervalTopicReuseStrategy = SameIntervalTopicReuseStrategy.SINGLE_TOPIC,
      dltTopicSuffix = "-dlt")
  @KafkaListener(topics = Topics.NOTIFICATION_REQUESTED, groupId = "${spring.kafka.consumer.group-id}")
  public void onNotificationRequested(NotificationRequested event,
      // Written by the retry machinery when it forwards a record, counting the try the
      // record is about to get: absent on the main topic, then 2, 3, ...
      @Header(name = RetryTopicHeaders.DEFAULT_HEADER_ATTEMPTS, required = false) Integer attempt) {
    log.debug("consumed event {} for recipient {}", event.eventId(), event.recipientId());
    try {
      metrics.timeDispatch(event.channel(), () -> dispatchService.dispatch(event));
    } catch (RuntimeException e) {
      // Recorded here rather than in the dispatcher because every failure surfaces here --
      // the claim, the render, a missing sender and the send itself -- and because only the
      // listener knows which try this was.
      log.warn("delivery of event {} failed: {}", event.eventId(), e.toString());
      boolean retriesRemaining = (attempt == null ? 1 : attempt) < maxAttempts;
      recorder.recordFailedAttempt(event, e, retriesRemaining);
      metrics.failed(event.channel());
      // Rethrown on purpose: the retry topics and the DLT are driven by the exception
      // escaping this method, so swallowing it here would silently drop the event.
      throw e;
    }
  }

  /**
   * The event exhausted its tries, or failed in a way the retry machinery classes as fatal
   * and skipped them.
   *
   * <p>A record that never deserialized does not get here: it fails the same way on the DLT
   * as it did on the main topic, has no event id to record against, and is logged by the
   * container instead.
   */
  @DltHandler
  public void onDeadLettered(NotificationRequested event) {
    log.warn("event {} parked on {}", event.eventId(), Topics.NOTIFICATION_REQUESTED_DLT);
    // A redelivered DLT record, or one for an event that was sent after all, changes nothing
    // and so counts nothing.
    if (recorder.recordDeadLettered(event)) {
      metrics.deadLettered(event.channel());
    }
  }
}
