package com.notification.platform.api.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.notification.platform.api.security.TokenService;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;

/**
 * Issues the bearer tokens this API accepts.
 *
 * <p>A deliberate stand-in for a real identity provider -- one configured client
 * credential, checked in constant-ish time by string equality, no user store. It is here so
 * the API can be closed by default from day one; replacing it with Keycloak or Auth0 is a
 * later project's work.
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

  private final TokenService tokenService;

  public record TokenRequest(@NotBlank String clientId, @NotBlank String clientSecret) {
  }

  public record TokenResponse(String accessToken, String tokenType) {
  }

  @Operation(summary = "Exchange the client credential for a bearer token")
  @PostMapping("/token")
  public ResponseEntity<TokenResponse> token(@Valid @RequestBody TokenRequest request) {
    if (!tokenService.isValid(request.clientId(), request.clientSecret())) {
      // 401 with no body: never say which half of the credential was wrong.
      return ResponseEntity.status(401).build();
    }
    return ResponseEntity.ok(new TokenResponse(tokenService.issue(request.clientId()), "Bearer"));
  }
}
