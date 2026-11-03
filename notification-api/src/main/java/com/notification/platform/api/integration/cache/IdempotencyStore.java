package com.notification.platform.api.integration.cache;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * Redis-backed memory of which {@code Idempotency-Key}s have already been published.
 *
 * <p>An entry is written only <em>after</em> the broker has acknowledged the event, so its
 * presence means exactly that: this key's event is on the topic. Nothing is claimed up front
 * and nothing has to be released when a publish fails, so a Redis outage on the failure path
 * cannot leave a key that swallows the client's retry (ADR 0006).
 *
 * <p>What this store decides is the replay answer -- the stored event id and
 * {@code duplicate=true} -- not whether a retry is safe. The event id is derived from the key,
 * so a retry that misses Redis republishes the same event, and the worker absorbs the copy.
 */
@Component
@RequiredArgsConstructor
public class IdempotencyStore {

  private static final String KEY_PREFIX = "notification:idempotency:";

  private final StringRedisTemplate redis;

  @Value("${notification.idempotency.ttl:PT24H}")
  private Duration ttl;

  /** The event id already published under {@code key}, if this request is a replay. */
  public Optional<UUID> findPublished(String key) {
    return Optional.ofNullable(redis.opsForValue().get(KEY_PREFIX + key)).map(UUID::fromString);
  }

  /**
   * Remember that {@code key}'s event has been acknowledged by the broker.
   *
   * <p>{@code SET NX}, so of two concurrent requests under one key that both published, only
   * one is told it was first. The other is a replay of the same event, and says so.
   *
   * @return whether this call wrote the entry; false when another request already had
   */
  public boolean recordPublished(String key, UUID eventId) {
    return Boolean.TRUE.equals(
        redis.opsForValue().setIfAbsent(KEY_PREFIX + key, eventId.toString(), ttl));
  }
}
