package com.notification.platform.worker.listener;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.SameIntervalTopicReuseStrategy;
import org.springframework.kafka.annotation.BackOff;
import org.springframework.stereotype.Component;

import com.notification.platform.common.event.NotificationRequested;
import com.notification.platform.common.event.Topics;
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
 * configured attempts the event lands on {@link Topics#NOTIFICATION_REQUESTED_DLT}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationRequestedListener {

  private final DispatchService dispatchService;
  private final DeliveryRecorder recorder;

  @RetryableTopic(
      attempts = "${notification.consumer.attempts:3}",
      backOff = @BackOff(delay = 1000, multiplier = 2.0, maxDelay = 8000),
      sameIntervalTopicReuseStrategy = SameIntervalTopicReuseStrategy.SINGLE_TOPIC,
      dltTopicSuffix = "-dlt")
  @KafkaListener(topics = Topics.NOTIFICATION_REQUESTED, groupId = "${spring.kafka.consumer.group-id}")
  public void onNotificationRequested(NotificationRequested event) {
    log.debug("consumed event {} for recipient {}", event.eventId(), event.recipientId());
    try {
      dispatchService.dispatch(event);
    } catch (RuntimeException e) {
      // Recorded here rather than in the dispatcher because the dispatch transaction has
      // rolled back by the time this runs: a failure written inside it would roll back with
      // it, and the attempt would leave no trace anywhere but the DLT.
      log.warn("delivery of event {} failed: {}", event.eventId(), e.toString());
      recorder.recordFailedAttempt(event, e);
      // Rethrown on purpose: the retry topics and the DLT are driven by the exception
      // escaping this method, so swallowing it here would silently drop the event.
      throw e;
    }
  }
}
