package com.notification.platform.worker.template;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.notification.platform.worker.template.TemplateProperties.Template;

/**
 * Turns a template key and a payload into the message to deliver.
 *
 * <p>Deliberately a pure function -- no repository, no clock, no I/O. The templates arrive
 * once at construction and everything else arrives as an argument, so its behaviour is
 * pinned by ordinary unit tests with no broker and no database. It is also the piece most
 * likely to be wrong in a way a recipient notices, which is the reason to make it the
 * cheapest piece to test.
 *
 * <p>Strict on both counts: an unknown key and an unresolved variable both throw. Half a
 * template is worse than no delivery -- "Hi , your order shipped" reaches a real person and
 * cannot be recalled, while a failure is recorded on the attempt and parked on the
 * dead-letter topic where someone can see it.
 */
@Component
public class TemplateRenderer {

  /**
   * {@code {{name}}}, tolerating inner whitespace. Not {@code ${name}}, which Spring would
   * try to resolve while binding the properties themselves.
   */
  private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([\\w.-]+)\\s*}}");

  private final Map<String, Template> templates;

  public TemplateRenderer(TemplateProperties properties) {
    this.templates = properties.templates();
  }

  /**
   * Render one notification's copy.
   *
   * @throws IllegalStateException if the key is not configured, or the payload is missing a
   *                               variable the template asks for
   */
  public RenderedMessage render(String templateKey, Map<String, String> payload) {
    Template template = templates.get(templateKey);
    if (template == null) {
      throw new IllegalStateException("no template configured for key " + templateKey);
    }

    Map<String, String> variables = payload == null ? Map.of() : payload;
    return new RenderedMessage(
        fill(templateKey, template.subject(), variables),
        fill(templateKey, template.body(), variables));
  }

  private static String fill(String templateKey, String text, Map<String, String> variables) {
    Matcher matcher = PLACEHOLDER.matcher(text);
    StringBuilder out = new StringBuilder();

    while (matcher.find()) {
      String name = matcher.group(1);
      String value = variables.get(name);
      if (value == null) {
        throw new IllegalStateException("template " + templateKey + " needs variable " + name
            + ", which the payload does not carry");
      }
      // quoteReplacement, because appendReplacement reads $ and \ in the replacement as
      // group references: an order id of "A$1" would otherwise blow up mid-render.
      matcher.appendReplacement(out, Matcher.quoteReplacement(value));
    }

    matcher.appendTail(out);
    return out.toString();
  }
}
