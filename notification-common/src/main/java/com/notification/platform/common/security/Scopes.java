package com.notification.platform.common.security;

/**
 * Token scopes, in one place so the side that mints them and the two that check them cannot
 * drift apart.
 *
 * <p>The constants are the words of the {@code scope} claim; Spring Security's default
 * converter splits that claim on spaces and prefixes each word with {@code SCOPE_} to make
 * an authority, which is the form the security configs match on.
 */
public final class Scopes {

  /** Read a delivery status. Served by the worker, which owns the tables. */
  public static final String READ = "notifications.read";

  /** Submit a notification request. Served by the api. */
  public static final String WRITE = "notifications.write";

  public static final String READ_AUTHORITY = "SCOPE_" + READ;

  public static final String WRITE_AUTHORITY = "SCOPE_" + WRITE;

  private Scopes() {
  }
}
