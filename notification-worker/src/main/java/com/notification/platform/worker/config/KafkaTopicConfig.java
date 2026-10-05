package com.notification.platform.worker.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

import com.notification.platform.common.event.Topics;

/**
 * Declares {@code notification.requested} here too, not only on the api. The worker only
 * consumes this topic, so on its own it relies on broker auto-creation -- which gives one
 * partition. When the worker's consumer reaches the broker before the api has started, that
 * one-partition topic is what gets cached; the api later widens it to three, but a consumer
 * holding the stale metadata keeps reading partition 0 alone until {@code metadata.max.age.ms}
 * expires, and events keyed onto the other partitions sit unprocessed.
 *
 * <p>Declaring the topic in both applications means whichever boots first creates it with the
 * full partition count, so neither side depends on auto-creation. The DLT stays the api's to
 * declare; here it is created on demand by the retry machinery.
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
}
