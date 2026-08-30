package com.notification.platform.worker.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

import com.notification.platform.common.event.NotificationChannel;
import com.notification.platform.worker.channel.EmailNotificationSender;
import com.notification.platform.worker.channel.LoggingNotificationSender;
import com.notification.platform.worker.channel.NotificationSender;

/**
 * One sender per channel.
 *
 * <p>Every channel in the contract gets one, on purpose: a channel with no sender fails at
 * dispatch and ends up on the dead-letter topic, and discovering that from a DLT message is
 * worse than discovering it here.
 *
 * <p>EMAIL is real and talks SMTP; SMS and PUSH are still stand-ins that log. Swapping one
 * of those for a provider is this bean and nothing else -- the dispatcher looks senders up
 * by channel and never names an implementation.
 */
@Configuration
public class SenderConfig {

  @Bean
  public NotificationSender emailSender(JavaMailSender mail,
      @Value("${notification.email.from}") String from) {
    return new EmailNotificationSender(mail, from);
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
