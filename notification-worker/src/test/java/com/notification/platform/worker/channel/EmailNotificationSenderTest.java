package com.notification.platform.worker.channel;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import com.notification.platform.common.event.NotificationChannel;
import com.notification.platform.worker.domain.Notification;
import com.notification.platform.worker.template.RenderedMessage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The first sender that speaks a real protocol, tested against a real one.
 *
 * <p>GreenMail is an SMTP server in this JVM, so this stays an ordinary unit test: no Spring
 * context, no container, no Docker. What it does cover is the part a mock could not -- that
 * the message actually leaves, addressed and formatted as the template rendered it.
 */
class EmailNotificationSenderTest {

  @RegisterExtension
  static final GreenMailExtension SMTP = new GreenMailExtension(ServerSetupTest.SMTP);

  private static final RenderedMessage MESSAGE = new RenderedMessage(
      "Your order A-1001 has shipped", "Order A-1001 is on its way.");

  @Test
  void deliversTheRenderedMessageToTheDestination() throws Exception {
    sender(ServerSetupTest.SMTP.getPort()).send(notification(), MESSAGE);

    assertThat(SMTP.waitForIncomingEmail(1)).isTrue();

    MimeMessage received = SMTP.getReceivedMessages()[0];
    assertThat(received.getAllRecipients()[0]).hasToString("jason@example.com");
    assertThat(received.getFrom()[0]).hasToString("notifications@platform.test");
    assertThat(received.getSubject()).isEqualTo("Your order A-1001 has shipped");
    assertThat(GreenMailUtil.getBody(received)).contains("Order A-1001 is on its way.");
  }

  @Test
  void letsAFailureOutSoTheEventCanBeRetried() {
    // Nothing is listening on this port. A sender that swallowed the failure would mark the
    // notification SENT and the message would be lost silently, which is the one behaviour
    // NotificationSender forbids.
    assertThatThrownBy(() -> sender(1).send(notification(), MESSAGE))
        .isInstanceOf(MailException.class);
  }

  private static EmailNotificationSender sender(int port) {
    JavaMailSenderImpl mail = new JavaMailSenderImpl();
    mail.setHost(ServerSetupTest.SMTP.getBindAddress());
    mail.setPort(port);
    // Otherwise the unreachable-port test waits out the platform default.
    mail.getJavaMailProperties().setProperty("mail.smtp.connectiontimeout", "2000");
    mail.getJavaMailProperties().setProperty("mail.smtp.timeout", "2000");
    return new EmailNotificationSender(mail, "notifications@platform.test");
  }

  private static Notification notification() {
    Instant at = Instant.parse("2026-08-27T21:30:00Z");
    return new Notification(UUID.randomUUID(), "user-42", "jason@example.com",
        NotificationChannel.EMAIL, "order-shipped", Map.of("orderId", "A-1001"), at, at);
  }
}
