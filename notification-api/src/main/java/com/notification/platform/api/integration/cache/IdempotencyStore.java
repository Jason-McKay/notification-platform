package com.notification.platform.api.integration.cache;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * Redis-backed claim on an {@code Idempotency-Key}.
 *
 * <p>A single {@code SET key value NX EX ttl} is the whole mechanism: whoever sets the key
 * first owns the request, and everyone else is told which event id already exists. Doing
 * it in one round trip is the point -- a GET-then-SET would let two concurrent retries of
 * the same request both decide they were first.
 */
@Component
@RequiredArgsConstructor
public class IdempotencyStore {

  private static final String KEY_PREFIX = "notification:idempotency:";

  private final StringRedisTemplate redis;

  @Value("${notification.idempotency.ttl:PT24H}")
  private Duration ttl;

  /**
   * Claim {@code key} for {@code eventId}.
   *
   * @return empty when the claim was won and the caller should go on to publish; the
   *         already-stored event id when this request is a replay
   */
  public Optional<UUID> claim(String key, UUID eventId) {
    String redisKey = KEY_PREFIX + key;
    Boolean won = redis.opsForValue().setIfAbsent(redisKey, eventId.toString(), ttl);
    if (Boolean.TRUE.equals(won)) {
      return Optional.empty();
    }
    // Not null-safe by accident: the key can expire between the SETNX and this GET, in
    // which case there is nothing to report as a duplicate and letting the request through
    // is the right answer -- it is a replay of something already forgotten.
    String existing = redis.opsForValue().get(redisKey);
    return Optional.ofNullable(existing).map(UUID::fromString);
  }

  /**
   * Drop a claim. Called when publishing failed, so the client's retry is not rejected as
   * a duplicate of an event that never reached the topic.
   */
  public void release(String key) {
    redis.delete(KEY_PREFIX + key);
  }
}
