package com.notification.platform.worker.config;

import java.util.Map;

import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;

import com.notification.platform.common.event.NotificationRequested;

/**
 * The producer the retry and dead-letter machinery publishes with.
 *
 * <p>This module consumes; the only thing it produces is a record being moved to a retry
 * topic or the DLT. Those two cases carry <em>different</em> value types, which is why a
 * plain serializer will not do:
 *
 * <ul>
 *   <li>a record that failed <em>in the listener</em> was deserialized fine, so its value is
 *       a {@link NotificationRequested};</li>
 *   <li>a record that failed <em>to deserialize</em> still holds its raw {@code byte[]}.</li>
 * </ul>
 *
 * <p>Without this, Boot's default {@code StringSerializer} rejects the byte[] with "Can't
 * convert value of class [B", the dead-letter publication fails, the container seeks back,
 * and one poison record stalls its partition for ever -- the exact failure
 * {@code ErrorHandlingDeserializer} was added to prevent. Found by hand, then pinned by
 * {@code NotificationDispatchIT.poisonRecordEndsUpOnTheDeadLetterTopic}.
 */
@Configuration
public class KafkaProducerConfig {

  @Bean
  public KafkaTemplate<String, Object> kafkaTemplate(KafkaProperties properties) {
    JacksonJsonSerializer<Object> jsonSerializer = new JacksonJsonSerializer<>();
    // Same as the api: no class name travels on the wire.
    jsonSerializer.setAddTypeInfo(false);

    DelegatingByTypeSerializer valueSerializer = new DelegatingByTypeSerializer(Map.of(
        byte[].class, new ByteArraySerializer(),
        NotificationRequested.class, jsonSerializer));

    Map<String, Object> props = properties.buildProducerProperties();
    return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(
        props, new StringSerializer(), valueSerializer));
  }
}
