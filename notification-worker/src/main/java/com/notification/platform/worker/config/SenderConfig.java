package com.notification.platform.worker.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.notification.platform.common.event.NotificationChannel;
import com.notification.platform.worker.channel.LoggingNotificationSender;
import com.notification.platform.worker.channel.NotificationSender;

/**
 * Registers a stand-in sender per channel.
 *
 * <p>Every channel in the contract gets one, on purpose: a channel with no sender fails at
 * dispatch and ends up on the dead-letter topic, and discovering that from a DLT message is
 * worse than discovering it here.
 */
@Configuration
public class SenderConfig {

  @Bean
  public NotificationSender emailSender() {
    return new LoggingNotificationSender(NotificationChannel.EMAIL);
  }

  @Bean
  public NotificationSender smsSender() {
    return new LoggingNotificationSender(NotificationChannel.SMS);
  }

  @Bean
  public NotificationSender pushSender() {
    return new LoggingNotificationSender(NotificationChannel.PUSH);
  }
}
