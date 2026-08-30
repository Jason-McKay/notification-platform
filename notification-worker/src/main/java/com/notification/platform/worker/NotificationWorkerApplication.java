package com.notification.platform.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import com.notification.platform.worker.template.TemplateProperties;

@SpringBootApplication
@EnableConfigurationProperties(TemplateProperties.class)
public class NotificationWorkerApplication {

  public static void main(String[] args) {
    SpringApplication.run(NotificationWorkerApplication.class, args);
  }
}
