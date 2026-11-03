package com.notification.platform.api.config;

import java.util.HashMap;
import java.util.Map;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import com.notification.platform.common.event.NotificationRequested;

/**
 * A template typed to the event we actually publish.
 *
 * <p>Boot's autoconfigured template is a {@code KafkaTemplate<Object, Object>}, which does
 * not satisfy an injection point typed to the event -- Spring's generic-aware injection
 * rejects it, at startup, with "no qualifying bean". Declaring the typed one here keeps
 * {@code NotificationEventPublisher} honest about what it sends instead of widening it to
 * Object to suit the framework. It reads the same {@code spring.kafka.producer.*}
 * configuration Boot binds, apart from the two delivery settings below.
 */
@Configuration
public class KafkaProducerConfig {

  @Bean
  public KafkaTemplate<String, NotificationRequested> notificationKafkaTemplate(
      KafkaProperties properties) {
    Map<String, Object> config = new HashMap<>(properties.buildProducerProperties());
    // Set in code so no property can weaken them. acks=all because the api answers 202 only
    // once the broker has the event, and a weaker ack would be lying to the client.
    // Idempotence has been the producer's default since Kafka 3.0, but it is dropped
    // *silently* when another setting conflicts with it (acks=1, say); asking for it
    // explicitly turns that conflict into a ConfigException at startup instead.
    // It covers the producer's own retries of one send -- no duplicate from a lost ack
    // inside the producer -- and nothing more. A second publish() after a timeout is a new
    // send, which only the event id derived from the Idempotency-Key makes safe (ADR 0006).
    config.put(ProducerConfig.ACKS_CONFIG, "all");
    config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
    return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(config));
  }
}
