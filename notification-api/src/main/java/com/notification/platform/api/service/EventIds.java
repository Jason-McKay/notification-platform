package com.notification.platform.api.service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * The event id a request carries when the client sent an {@code Idempotency-Key}.
 *
 * <p>Derived, not random, so that a retry under the same key is the same event: if the first
 * publish did reach the topic after all, the worker's primary key absorbs the second copy
 * instead of delivering it twice (ADR 0006). A name-based UUID (version 5, SHA-1) over a
 * namespace of this application's own plus the key, as RFC 9562 defines it.
 *
 * <p>Here rather than in notification-common: how the api dedupes is its own policy, not part
 * of the wire contract. The worker treats the id as opaque and must never need this.
 */
public final class EventIds {

  /**
   * The namespace every key is derived under. <strong>Never change it.</strong> A different
   * namespace re-keys every request, so a client retrying across that deploy gets a new event
   * id for an event that may already be on the topic, and is delivered twice -- the hole this
   * class exists to close.
   */
  public static final UUID NAMESPACE = UUID.fromString("77e16988-ab8d-48d8-a824-7b2747f6e81e");

  private EventIds() {
  }

  /** The event id for {@code idempotencyKey}, the same on every call and every instance. */
  public static UUID forIdempotencyKey(String idempotencyKey) {
    return v5(NAMESPACE, idempotencyKey);
  }

  /** A version 5 UUID: SHA-1 over the namespace's 16 bytes then the name's UTF-8 bytes. */
  static UUID v5(UUID namespace, String name) {
    MessageDigest sha1;
    try {
      sha1 = MessageDigest.getInstance("SHA-1");
    } catch (NoSuchAlgorithmException e) {
      // Every Java platform is required to provide SHA-1, so this cannot happen.
      throw new IllegalStateException(e);
    }
    sha1.update(ByteBuffer.allocate(16)
        .putLong(namespace.getMostSignificantBits())
        .putLong(namespace.getLeastSignificantBits())
        .array());
    byte[] hash = sha1.digest(name.getBytes(StandardCharsets.UTF_8));

    hash[6] = (byte) ((hash[6] & 0x0f) | 0x50); // version 5
    hash[8] = (byte) ((hash[8] & 0x3f) | 0x80); // the RFC 4122 / 9562 variant
    ByteBuffer bits = ByteBuffer.wrap(hash, 0, 16);
    return new UUID(bits.getLong(), bits.getLong());
  }
}
