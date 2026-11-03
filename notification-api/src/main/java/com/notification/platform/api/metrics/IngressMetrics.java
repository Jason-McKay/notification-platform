package com.notification.platform.api.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * What the ingress path counts, and how long the awaited publish takes.
 *
 * <p>The outcomes are what the client was told. {@code accepted} is a 202 with
 * {@code duplicate=false}. {@code duplicate} is a 202 with {@code duplicate=true}: a replay
 * found in Redis, or a request that lost the record race to a concurrent one under the same
 * key. {@code publish_failed} is a publish that threw, which the client sees as a 5xx. A Redis
 * failure before anything was published is none of these, and nothing here counts it.
 *
 * <p>Every meter is registered up front, so the series exist at zero from startup, and the
 * cardinality is fixed: nothing is ever tagged by recipient, idempotency key or event id --
 * see ADR 0005, {@code docs/adr/0005-delivery-states-and-the-read-api.md}.
 */
@Component
public class IngressMetrics {

  static final String INGRESS = "notification.ingress";
  static final String PUBLISH_DURATION = "notification.publish.duration";

  private final Counter accepted;
  private final Counter duplicate;
  private final Counter publishFailed;
  private final Timer publishes;

  public IngressMetrics(MeterRegistry registry) {
    accepted = ingress(registry, "accepted");
    duplicate = ingress(registry, "duplicate");
    publishFailed = ingress(registry, "publish_failed");
    publishes = Timer.builder(PUBLISH_DURATION)
        .description("Time to publish one event and await the ack, whether it came or not")
        .publishPercentileHistogram()
        .register(registry);
  }

  public void accepted() {
    accepted.increment();
  }

  /** A replay, or the loser of a record race: either way the client is told duplicate=true. */
  public void duplicate() {
    duplicate.increment();
  }

  public void publishFailed() {
    publishFailed.increment();
  }

  /** Times the publish whether it returns or throws, and lets anything thrown through. */
  public void timePublish(Runnable publish) {
    publishes.record(publish);
  }

  private static Counter ingress(MeterRegistry registry, String outcome) {
    return Counter.builder(INGRESS)
        .description("Ingress outcomes, as the client was told them")
        .tag("outcome", outcome)
        .register(registry);
  }
}
