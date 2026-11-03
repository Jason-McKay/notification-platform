package com.notification.platform.api.config;

import java.util.List;
import java.util.Map;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.kafka.core.KafkaTemplate;

import com.notification.platform.common.event.NotificationRequested;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The delivery settings the 202 relies on. Pinned against configuration that tries to weaken
 * them, because a weaker ack is a 202 for an event the broker may not keep, and a disabled
 * idempotent producer is a duplicate whenever the producer retries a send whose ack was lost.
 */
class KafkaProducerConfigTest {

  @Test
  void propertiesCannotWeakenTheDeliverySettings() {
    KafkaProperties properties = new KafkaProperties();
    properties.getProducer().setAcks("1");
    properties.getProducer().getProperties().put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "false");

    KafkaTemplate<String, NotificationRequested> template =
        new KafkaProducerConfig().notificationKafkaTemplate(properties);

    Map<String, Object> config = template.getProducerFactory().getConfigurationProperties();
    assertThat(config)
        .containsEntry(ProducerConfig.ACKS_CONFIG, "all")
        .containsEntry(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
  }

  @Test
  void keepsTheRestOfTheBoundConfiguration() {
    KafkaProperties properties = new KafkaProperties();
    properties.setBootstrapServers(List.of("broker-1:9092"));

    KafkaTemplate<String, NotificationRequested> template =
        new KafkaProducerConfig().notificationKafkaTemplate(properties);

    assertThat(template.getProducerFactory().getConfigurationProperties())
        .containsEntry(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, List.of("broker-1:9092"));
  }
}
