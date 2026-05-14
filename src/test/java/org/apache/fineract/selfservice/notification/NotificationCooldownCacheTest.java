/**
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * <p>http://www.apache.org/licenses/LICENSE-2.0
 *
 * <p>Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.fineract.selfservice.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class NotificationCooldownCacheTest {

  private static final Duration TTL = Duration.ofSeconds(60);
  private static final int MAX_SIZE = 100;

  // ---- Constructor validation ----

  @Test
  void constructor_rejectsNullTtl() {
    assertThrows(NullPointerException.class, () -> new NotificationCooldownCache(null, MAX_SIZE));
  }

  @Test
  void constructor_rejectsZeroTtl() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new NotificationCooldownCache(Duration.ZERO, MAX_SIZE));
  }

  @Test
  void constructor_rejectsNegativeTtl() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new NotificationCooldownCache(Duration.ofSeconds(-1), MAX_SIZE));
  }

  @Test
  void constructor_rejectsZeroMaxSize() {
    assertThrows(IllegalArgumentException.class, () -> new NotificationCooldownCache(TTL, 0));
  }

  @Test
  void constructor_rejectsNegativeMaxSize() {
    assertThrows(IllegalArgumentException.class, () -> new NotificationCooldownCache(TTL, -5));
  }

  // ---- tryAcquire ----

  @Test
  void tryAcquire_firstCall_returnsTrue() {
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    NotificationCooldownCache cache = new NotificationCooldownCache(TTL, MAX_SIZE, clock);

    assertTrue(cache.tryAcquire("LOGIN:1"));
  }

  @Test
  void tryAcquire_withinCooldown_returnsFalse() {
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    NotificationCooldownCache cache = new NotificationCooldownCache(TTL, MAX_SIZE, clock);

    assertTrue(cache.tryAcquire("LOGIN:1"));
    assertFalse(cache.tryAcquire("LOGIN:1"), "Second acquire within TTL should return false");
  }

  @Test
  void tryAcquire_differentKeys_bothSucceed() {
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    NotificationCooldownCache cache = new NotificationCooldownCache(TTL, MAX_SIZE, clock);

    assertTrue(cache.tryAcquire("LOGIN:1"));
    assertTrue(cache.tryAcquire("LOGIN:2"), "Different keys should not interfere");
  }

  @Test
  void tryAcquire_afterExpiry_returnsTrue() {
    Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
    Instant afterTtl = t0.plus(TTL).plusSeconds(1);

    MutableClock clock = new MutableClock(t0);
    NotificationCooldownCache cache = new NotificationCooldownCache(TTL, MAX_SIZE, clock);

    assertTrue(cache.tryAcquire("LOGIN:1"));
    assertFalse(cache.tryAcquire("LOGIN:1"));

    // Advance past TTL
    clock.setInstant(afterTtl);

    assertTrue(cache.tryAcquire("LOGIN:1"), "Should re-acquire after TTL has expired");
  }

  // ---- isOnCooldown ----

  @Test
  void isOnCooldown_returnsFalseForUnknownKey() {
    Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    NotificationCooldownCache cache = new NotificationCooldownCache(TTL, MAX_SIZE, clock);

    assertFalse(cache.isOnCooldown("UNKNOWN:99"));
  }

  @Test
  void isOnCooldown_returnsTrueWhileActive() {
    MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    NotificationCooldownCache cache = new NotificationCooldownCache(TTL, MAX_SIZE, clock);

    cache.tryAcquire("LOGIN:1");
    assertTrue(cache.isOnCooldown("LOGIN:1"));
  }

  @Test
  void isOnCooldown_returnsFalseAfterExpiry() {
    Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
    MutableClock clock = new MutableClock(t0);
    NotificationCooldownCache cache = new NotificationCooldownCache(TTL, MAX_SIZE, clock);

    cache.tryAcquire("LOGIN:1");
    assertTrue(cache.isOnCooldown("LOGIN:1"));

    clock.setInstant(t0.plus(TTL).plusSeconds(1));
    assertFalse(cache.isOnCooldown("LOGIN:1"), "Should not be on cooldown after TTL expires");
  }

  // ---- release ----

  @Test
  void release_allowsImmediateReacquire() {
    Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    NotificationCooldownCache cache = new NotificationCooldownCache(TTL, MAX_SIZE, clock);

    assertTrue(cache.tryAcquire("LOGIN:1"));
    assertFalse(cache.tryAcquire("LOGIN:1"));

    cache.release("LOGIN:1");

    assertTrue(cache.tryAcquire("LOGIN:1"), "Should re-acquire immediately after explicit release");
  }

  @Test
  void release_noopForUnknownKey() {
    Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    NotificationCooldownCache cache = new NotificationCooldownCache(TTL, MAX_SIZE, clock);

    // Should not throw
    cache.release("NONEXISTENT:99");
  }

  // ---- Maximum size eviction ----

  @Test
  void maximumSize_evictsEntry_whenCapacityExceeded() {
    // Use maxSize=2 so eviction triggers on the 3rd entry.
    // ConcurrentHashMap iteration order is not guaranteed, but the cache's removeOneEntry()
    // removes whichever key the iterator yields first. We assert that the cache does NOT
    // exceed maxSize, rather than asserting which specific key was evicted.
    Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    NotificationCooldownCache cache = new NotificationCooldownCache(TTL, 2, clock);

    assertTrue(cache.tryAcquire("A"));
    assertTrue(cache.tryAcquire("B"));
    assertTrue(cache.tryAcquire("C"), "Third acquire should succeed (evicts one existing entry)");

    // At most 2 of the 3 keys should still be on cooldown
    int onCooldown = 0;
    if (cache.isOnCooldown("A")) onCooldown++;
    if (cache.isOnCooldown("B")) onCooldown++;
    if (cache.isOnCooldown("C")) onCooldown++;

    assertEquals(2, onCooldown, "Exactly 2 entries should remain after eviction (maxSize=2)");
  }

  // ---- MutableClock helper for time-based tests ----

  /** A simple mutable clock for testing. Allows advancing time without rebuilding the cache. */
  private static class MutableClock extends Clock {

    private Instant instant;

    MutableClock(Instant initial) {
      this.instant = initial;
    }

    void setInstant(Instant instant) {
      this.instant = instant;
    }

    @Override
    public java.time.ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this; // Ignore zone changes for test simplicity
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }
}
