package com.notification.platform.worker.channel;

import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import com.notification.platform.common.event.NotificationChannel;
import com.notification.platform.worker.domain.Notification;
import com.notification.platform.worker.template.RenderedMessage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Delivers EMAIL over SMTP. The first sender in the platform that can genuinely fail.
 *
 * <p>It catches nothing, on purpose. Spring's {@code MailException} is unchecked, so a
 * rejected or undeliverable message propagates out of {@code send} to the listener, which
 * records the attempt and lets the retry topics carry the event towards the dead-letter
 * topic. Handling it here would turn a failed delivery into a silent SENT -- the one thing
 * {@link NotificationSender} tells implementations not to do.
 *
 * <p>A plain text message: the templates render one subject and one body, and there is no
 * HTML story yet. A multipart message is a change here and in the template shape, not in
 * anything between them.
 */
@RequiredArgsConstructor
@Slf4j
public class EmailNotificationSender implements NotificationSender {

  private final JavaMailSender mail;
  private final String from;

  @Override
  public NotificationChannel channel() {
    return NotificationChannel.EMAIL;
  }

  @Override
  public void send(Notification notification, RenderedMessage message) {
    SimpleMailMessage email = new SimpleMailMessage();
    email.setFrom(from);
    email.setTo(notification.getDestination());
    email.setSubject(message.subject());
    email.setText(message.body());

    mail.send(email);

    log.info("[EMAIL] delivered to {} (event {})",
        notification.getDestination(), notification.getEventId());
  }
}
