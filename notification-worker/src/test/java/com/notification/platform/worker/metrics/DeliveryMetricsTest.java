package com.notification.platform.worker.metrics;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;

import com.notification.platform.common.event.NotificationChannel;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The series the dashboard and the alert rules in {@code observability/} are written against,
 * in the form Prometheus scrapes them. A renamed meter or a dropped histogram would otherwise
 * show up only as an empty panel or an alert that can never fire.
 */
class DeliveryMetricsTest {

  private final PrometheusMeterRegistry registry =
      new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

  @Test
  void everyChannelAndOutcomeExistsAtZeroBeforeTheFirstEvent() {
    new DeliveryMetrics(registry);

    String scrape = registry.scrape();
    for (NotificationChannel channel : NotificationChannel.values()) {
      for (String outcome : new String[] {"sent", "failed", "dead_lettered"}) {
        assertThat(scrape).contains("notification_delivery_total{channel=\"" + channel.name()
            + "\",outcome=\"" + outcome + "\"} 0.0");
      }
    }
  }

  @Test
  void dispatchDurationPublishesHistogramBuckets() {
    DeliveryMetrics metrics = new DeliveryMetrics(registry);

    metrics.timeDispatch(NotificationChannel.EMAIL, () -> { });

    // histogram_quantile() needs the _bucket series; client-side percentiles cannot be
    // aggregated across instances or re-windowed.
    assertThat(registry.scrape())
        .contains("notification_dispatch_duration_seconds_bucket{channel=\"EMAIL\"")
        .contains("notification_dispatch_duration_seconds_count{channel=\"EMAIL\"} 1");
  }
}
