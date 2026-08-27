package com.notification.platform.api.security;

import java.nio.charset.StandardCharsets;

import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import com.nimbusds.jose.jwk.source.ImmutableSecret;

/**
 * HMAC-SHA256 encoder and decoder off one shared secret, the same arrangement
 * message-inspector uses: symmetric signing means no keypair to manage for a service that
 * both mints and verifies its own tokens.
 *
 * <p>The secret comes from {@code notification.jwt.secret}; the dev default in
 * {@code application.properties} MUST be overridden by env anywhere real. Nimbus rejects
 * anything shorter than 256 bits for HS256.
 */
@Configuration
public class JwtConfig {

  private final SecretKeySpec secretKey;

  public JwtConfig(@Value("${notification.jwt.secret}") String secret) {
    this.secretKey = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
  }

  @Bean
  public JwtEncoder jwtEncoder() {
    return new NimbusJwtEncoder(new ImmutableSecret<>(secretKey));
  }

  @Bean
  public JwtDecoder jwtDecoder() {
    return NimbusJwtDecoder.withSecretKey(secretKey).macAlgorithm(MacAlgorithm.HS256).build();
  }
}
