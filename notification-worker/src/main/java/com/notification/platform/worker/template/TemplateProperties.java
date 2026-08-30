package com.notification.platform.worker.template;

import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The configured templates, keyed by the {@code templateKey} that travels on the event.
 *
 * <p>The only {@code @ConfigurationProperties} in either application, and deliberately so:
 * everything else here is a scalar and reads fine through {@code @Value}, but a map of
 * structured values is exactly what {@code @Value} cannot bind.
 *
 * <p>Configuration rather than a table because nothing yet needs a template to change
 * without a redeploy. A store is the answer once someone non-technical has to edit copy;
 * the renderer takes the templates as a map either way, so that change does not reach it.
 *
 * @param templates by template key; a key with no entry is a failed delivery, not a default
 */
@ConfigurationProperties("notification")
public record TemplateProperties(Map<String, Template> templates) {

  public TemplateProperties {
    templates = templates == null ? Map.of() : Map.copyOf(templates);
  }

  /**
   * One template's two renderable parts.
   *
   * <p>Both carry {@code {{name}}} placeholders. Not {@code ${name}}: these live in
   * application.properties, where Spring resolves {@code ${...}} itself while binding, and
   * a template variable would fail startup as an unresolvable placeholder.
   *
   * @param subject one line; for a channel with no subject (SMS) the sender ignores it
   * @param body    the message itself
   */
  public record Template(String subject, String body) {
  }
}
