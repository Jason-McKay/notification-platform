package com.notification.platform.api.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

import com.notification.platform.common.security.Scopes;

/**
 * Stateless bearer-token security. Every route needs a valid JWT except the token endpoint
 * itself, the health probe, {@code /error}, and the dev-only API docs.
 *
 * <p>The write additionally needs {@code notifications.write}. A token that authenticates
 * but lacks the scope gets 403, not 401 -- we know who you are, you may not do this.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

  @Bean
  public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    http
      // Safe to disable: auth is a bearer token, not a cookie session, so there is no CSRF
      // vector. If auth ever moves to cookies, this has to come back.
      .csrf(csrf -> csrf.disable())
      .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
      .authorizeHttpRequests(auth -> auth
        .requestMatchers(
            // How you get a token, so it cannot itself require one.
            "/api/auth/token",
            // Must be permitted, or the container's forward to /error is rejected by this
            // chain and every unhandled error surfaces as a 403.
            "/error",
            "/actuator/health",
            // springdoc is disabled outside the dev profile, so these 404 in prod.
            "/api/swagger-ui.html",
            "/api/swagger-ui/**",
            "/api/api-docs/**")
          .permitAll()
        // Authenticated is not authorised: the write needs the scope that names it, or a
        // token minted for reading would be enough to publish.
        .requestMatchers(HttpMethod.POST, "/api/notifications").hasAuthority(Scopes.WRITE_AUTHORITY)
        .anyRequest().authenticated())
      .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> {
      }));
    return http.build();
  }
}
