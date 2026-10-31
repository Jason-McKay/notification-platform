package com.notification.platform.worker.metrics;

import java.util.EnumMap;
import java.util.Map;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import com.notification.platform.common.event.NotificationChannel;

/**
 * What the delivery path counts, and how long a dispatch takes.
 *
 * <p>Every meter is registered up front, one per channel and outcome, so the series exist
 * at zero from startup rather than appearing on the first event, and the cardinality is
 * fixed by the enums. Nothing here is ever tagged by recipient or event id -- see ADR 0005,
 * {@code docs/adr/0005-delivery-states-and-the-read-api.md}.
 *
 * <p>The callers count only after the recorder's transaction has committed, so a counter
 * never runs ahead of the delivery record it describes.
 */
@Component
public class DeliveryMetrics {

  static final String DELIVERIES = "notification.delivery";
  static final String DISPATCH_DURATION = "notification.dispatch.duration";

  private final Map<NotificationChannel, Counter> sent = new EnumMap<>(NotificationChannel.class);
  private final Map<NotificationChannel, Counter> failed = new EnumMap<>(NotificationChannel.class);
  private final Map<NotificationChannel, Counter> deadLettered =
      new EnumMap<>(NotificationChannel.class);
  private final Map<NotificationChannel, Timer> dispatches =
      new EnumMap<>(NotificationChannel.class);

  public DeliveryMetrics(MeterRegistry registry) {
    for (NotificationChannel channel : NotificationChannel.values()) {
      sent.put(channel, deliveries(registry, channel, "sent"));
      failed.put(channel, deliveries(registry, channel, "failed"));
      deadLettered.put(channel, deliveries(registry, channel, "dead_lettered"));
      dispatches.put(channel, Timer.builder(DISPATCH_DURATION)
          .description("Time to dispatch one event, whether it was sent or failed")
          .tag("channel", channel.name())
          .register(registry));
    }
  }

  public void sent(NotificationChannel channel) {
    sent.get(channel).increment();
  }

  /** Every failed try, including the ones the retry topics will give another go. */
  public void failed(NotificationChannel channel) {
    failed.get(channel).increment();
  }

  public void deadLettered(NotificationChannel channel) {
    deadLettered.get(channel).increment();
  }

  /** Times the dispatch whether it returns or throws, and lets anything thrown through. */
  public void timeDispatch(NotificationChannel channel, Runnable dispatch) {
    dispatches.get(channel).record(dispatch);
  }

  private static Counter deliveries(MeterRegistry registry, NotificationChannel channel,
      String outcome) {
    return Counter.builder(DELIVERIES)
        .description("Delivery outcomes: each sent event, each failed try, each parked event")
        .tag("channel", channel.name())
        .tag("outcome", outcome)
        .register(registry);
  }
}
