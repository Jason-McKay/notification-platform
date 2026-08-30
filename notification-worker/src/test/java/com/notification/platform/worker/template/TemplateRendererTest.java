package com.notification.platform.worker.template;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.notification.platform.worker.template.TemplateProperties.Template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The renderer is a pure function, so this is a plain unit test: no context, no broker, no
 * database. That is the whole reason for keeping it pure.
 */
class TemplateRendererTest {

  private final TemplateRenderer renderer = new TemplateRenderer(new TemplateProperties(Map.of(
      "order-shipped", new Template(
          "Your order {{orderId}} has shipped",
          "Hi {{name}}, order {{orderId}} is on its way."),
      "no-variables", new Template("Welcome", "Nothing to fill in here."))));

  @Test
  void fillsPlaceholdersInBothSubjectAndBody() {
    RenderedMessage message = renderer.render("order-shipped",
        Map.of("orderId", "A-1001", "name", "Jason"));

    assertThat(message.subject()).isEqualTo("Your order A-1001 has shipped");
    assertThat(message.body()).isEqualTo("Hi Jason, order A-1001 is on its way.");
  }

  @Test
  void leavesATemplateWithNoPlaceholdersAlone() {
    RenderedMessage message = renderer.render("no-variables", Map.of());

    assertThat(message.subject()).isEqualTo("Welcome");
    assertThat(message.body()).isEqualTo("Nothing to fill in here.");
  }

  @Test
  void takesAValueLiterally() {
    // $ and \ mean something to Matcher.appendReplacement. A payload value is data, not a
    // pattern, and an order id like this one used to blow the render up.
    TemplateRenderer literal = new TemplateRenderer(new TemplateProperties(
        Map.of("k", new Template("{{v}}", "{{v}}"))));

    assertThat(literal.render("k", Map.of("v", "A$1\\B")).body()).isEqualTo("A$1\\B");
  }

  @Test
  void rejectsAnUnknownTemplateKey() {
    assertThatThrownBy(() -> renderer.render("no-such-template", Map.of("orderId", "A-1001")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no-such-template");
  }

  @Test
  void rejectsAPayloadMissingAVariableTheTemplateAsksFor() {
    // Strict on purpose: "Hi , order A-1001 is on its way" reaches a real person and cannot
    // be recalled, whereas this throw becomes a recorded failure and a dead-lettered event.
    assertThatThrownBy(() -> renderer.render("order-shipped", Map.of("orderId", "A-1001")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("name");
  }
}
