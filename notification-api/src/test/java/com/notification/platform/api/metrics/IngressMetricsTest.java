package com.notification.platform.api.metrics;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The series a dashboard and an alert are written against. They must exist at zero before the
 * first request, or a panel reads "no data" and a rate over a fresh series misses its first
 * increment.
 */
class IngressMetricsTest {

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final IngressMetrics metrics = new IngressMetrics(registry);

  @Test
  void everyOutcomeExistsAtZeroBeforeTheFirstRequest() {
    assertThat(registry.find(IngressMetrics.INGRESS).counters())
        .extracting(counter -> counter.getId().getTag("outcome"))
        .containsExactlyInAnyOrder("accepted", "duplicate", "publish_failed");
    assertThat(registry.find(IngressMetrics.INGRESS).counters())
        .allSatisfy(counter -> assertThat(counter.count()).isZero());
    assertThat(registry.find(IngressMetrics.PUBLISH_DURATION).timer()).isNotNull();
  }

  @Test
  void eachOutcomeCountsOnlyItself() {
    metrics.accepted();
    metrics.accepted();
    metrics.duplicate();
    metrics.publishFailed();

    assertThat(count("accepted")).isEqualTo(2);
    assertThat(count("duplicate")).isEqualTo(1);
    assertThat(count("publish_failed")).isEqualTo(1);
  }

  @Test
  void aPublishIsTimedWhetherItReturnsOrThrows() {
    metrics.timePublish(() -> { });
    assertThatThrownBy(() -> metrics.timePublish(() -> {
      throw new IllegalStateException("broker down");
    })).isInstanceOf(IllegalStateException.class);

    Timer publishes = registry.get(IngressMetrics.PUBLISH_DURATION).timer();
    assertThat(publishes.count()).isEqualTo(2);
  }

  private double count(String outcome) {
    return registry.get(IngressMetrics.INGRESS).tag("outcome", outcome).counter().count();
  }
}
