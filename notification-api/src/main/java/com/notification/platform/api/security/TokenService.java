package com.notification.platform.api.security;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.stereotype.Service;

import com.notification.platform.common.security.Scopes;

import lombok.RequiredArgsConstructor;

/**
 * Mints the service-to-service tokens this API accepts.
 *
 * <p>This is a stand-in, not an identity system: one configured client credential, no user
 * store, no refresh. It exists so the API is closed by default and the {@code http/} file
 * can drive it. A real IdP (Keycloak/Auth0) is the API Gateway project's job.
 */
@Service
@RequiredArgsConstructor
public class TokenService {

  private final JwtEncoder jwtEncoder;

  @Value("${notification.auth.client-id}")
  private String clientId;

  @Value("${notification.auth.client-secret}")
  private String clientSecret;

  @Value("${notification.auth.token-ttl:PT1H}")
  private java.time.Duration tokenTtl;

  public boolean isValid(String id, String secret) {
    return clientId.equals(id) && clientSecret.equals(secret);
  }

  public String issue(String id) {
    Instant now = Instant.now();
    JwtClaimsSet claims = JwtClaimsSet.builder()
        .issuer("notification-api")
        .issuedAt(now)
        .expiresAt(now.plus(tokenTtl.toSeconds(), ChronoUnit.SECONDS))
        .subject(id)
        // Both scopes on one token: there is a single client credential here, so the split
        // buys separation of the routes, not of the callers. A real IdP is where different
        // clients start getting different scopes.
        .claim("scope", Scopes.READ + " " + Scopes.WRITE)
        .build();
    return jwtEncoder.encode(JwtEncoderParameters.from(
        JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
  }
}
