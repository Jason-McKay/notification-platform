package com.notification.platform.api.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

import com.notification.platform.common.event.Topics;

/**
 * Declares the topics rather than relying on broker auto-creation, which gives one
 * partition and no say in it. Partitioning is the point here: the publisher keys by
 * recipient, so a recipient's notifications stay ordered while the worker still scales out
 * across partitions.
 *
 * <p>The DLT is declared too. Spring Kafka would create it on first use, but a dead-letter
 * topic that only exists after the first failure is one you cannot monitor beforehand.
 */
@Configuration
public class KafkaTopicConfig {

  @Value("${notification.topic.partitions}")
  private int partitions;

  @Value("${notification.topic.replicas}")
  private int replicas;

  @Bean
  public NewTopic notificationRequestedTopic() {
    return TopicBuilder.name(Topics.NOTIFICATION_REQUESTED)
        .partitions(partitions)
        .replicas(replicas)
        .build();
  }

  @Bean
  public NewTopic notificationRequestedDlt() {
    return TopicBuilder.name(Topics.NOTIFICATION_REQUESTED_DLT)
        .partitions(1)
        .replicas(replicas)
        .build();
  }
}
