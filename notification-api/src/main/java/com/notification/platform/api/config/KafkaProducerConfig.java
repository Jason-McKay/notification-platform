package com.notification.platform.api.config;

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
 * configuration Boot binds.
 */
@Configuration
public class KafkaProducerConfig {

  @Bean
  public KafkaTemplate<String, NotificationRequested> notificationKafkaTemplate(
      KafkaProperties properties) {
    return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(properties.buildProducerProperties()));
  }
}
