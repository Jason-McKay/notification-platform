package com.notification.platform.worker.security;

import java.nio.charset.StandardCharsets;

import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

import com.notification.platform.common.security.Scopes;

/**
 * The worker validates the same tokens the api issues, and mints none of its own -- decoder
 * only, no encoder, no token endpoint.
 *
 * <p>Both applications therefore share {@code notification.jwt.secret} in dev. That is the
 * known limitation of symmetric signing: the verifier holds the signing key. Moving to an
 * IdP with a JWKS endpoint is what fixes it, and is the API Gateway project's job.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

  private final SecretKeySpec secretKey;

  public SecurityConfig(@Value("${notification.jwt.secret}") String secret) {
    this.secretKey = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
  }

  @Bean
  public JwtDecoder jwtDecoder() {
    return NimbusJwtDecoder.withSecretKey(secretKey).macAlgorithm(MacAlgorithm.HS256).build();
  }

  @Bean
  public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    http
      .csrf(csrf -> csrf.disable())
      .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
      .authorizeHttpRequests(auth -> auth
        .requestMatchers("/error", "/actuator/health").permitAll()
        // The status read is a read: it takes the read scope, not the write one the api
        // guards its ingress with.
        .requestMatchers(HttpMethod.GET, "/api/notifications/**").hasAuthority(Scopes.READ_AUTHORITY)
        .anyRequest().authenticated())
      .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> {
      }));
    return http.build();
  }
}
