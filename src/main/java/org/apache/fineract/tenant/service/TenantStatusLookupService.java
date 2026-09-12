/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.tenant.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.tenant.domain.TenantStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Resolves a tenant's lifecycle status by identifier, with a short-lived cache.
 *
 * <p>Consulted by {@code TenantStatusEnforcementFilter} on every request, so an uncached lookup
 * would add a query to the tenant store to each one.
 *
 * <p><strong>Why a local cache and not Spring's.</strong> This runs in a servlet filter ahead of
 * the security chain, on a datasource the platform's cache configuration knows nothing about. A
 * hand-rolled map keeps the invalidation explicit and the behaviour identical regardless of how the
 * host installation has configured caching.
 *
 * <p>Entries are invalidated directly when this node changes a tenant, and expire on a timer so a
 * change made by <em>another</em> node - or by hand in the database - still takes effect without a
 * restart. The TTL is therefore the worst-case delay before a suspension takes hold across a
 * cluster.
 *
 * <p><strong>What is cached.</strong> Only answers about tenants that exist. The identifier comes
 * straight from a request header, so caching "no such tenant" would let anyone grow the cache
 * without bound by inventing identifiers. The number of real tenants bounds the cache, and {@link
 * #MAX_CACHED_TENANTS} backstops even that.
 */
@Service
@Slf4j
public class TenantStatusLookupService {

  /** The kinds of answer a lookup can give. */
  public enum Kind {
    /** No tenant holds the identifier. */
    NO_SUCH_TENANT,
    /** The registry could not be read, and no earlier status for the tenant is known. */
    REGISTRY_UNAVAILABLE,
    /** The tenant exists and its status is one this plugin understands. */
    KNOWN,
    /** The tenant exists but its stored status names nothing this plugin understands. */
    UNRECOGNISED
  }

  /**
   * The answer to one lookup.
   *
   * @param kind what kind of answer this is
   * @param status the tenant's status when {@code kind} is {@link Kind#KNOWN}, otherwise null
   */
  public record Lookup(Kind kind, TenantStatus status) {

    public static Lookup known(final TenantStatus status) {
      return new Lookup(Kind.KNOWN, status);
    }

    public static Lookup noSuchTenant() {
      return new Lookup(Kind.NO_SUCH_TENANT, null);
    }

    public static Lookup registryUnavailable() {
      return new Lookup(Kind.REGISTRY_UNAVAILABLE, null);
    }

    public static Lookup unrecognised() {
      return new Lookup(Kind.UNRECOGNISED, null);
    }

    /**
     * Whether requests to this tenant must be refused.
     *
     * <p>An unrecognised stored status refuses service: the column is only ever written with a
     * valid value by this plugin, so anything else is a hand edit or corruption, and
     * SOUL_GUARDRAILS requires deny-by-default when context is unclear.
     *
     * <p>A registry that cannot be read refuses too, but only when no earlier status is known for
     * the tenant - {@link TenantStatusLookupService#statusOf} falls back to that first. Core keeps
     * resolving tenants from its own {@code tenantsById} cache while the tenant store is down, so
     * letting an unverifiable request through could serve a suspended tenant.
     *
     * <p>A missing tenant does not refuse here; the platform's own tenant resolution runs next and
     * produces the proper error.
     *
     * @return true when the request must be refused
     */
    public boolean refusesService() {
      return kind == Kind.UNRECOGNISED
          || kind == Kind.REGISTRY_UNAVAILABLE
          || (kind == Kind.KNOWN && status != TenantStatus.ACTIVE);
    }
  }

  /** Backstop on cache size. Real tenant counts sit far below it. */
  static final int MAX_CACHED_TENANTS = 10_000;

  private record CachedStatus(Lookup lookup, Instant readAt) {}

  private final JdbcTemplate jdbcTemplate;
  private final Duration timeToLive;

  /**
   * How long past its cache expiry an ACTIVE status is still trusted while the tenant store cannot
   * be read. Bounded because another node may have suspended the tenant in the meantime.
   */
  private final Duration staleGrace;

  private final Map<String, CachedStatus> cache = new ConcurrentHashMap<>();

  /**
   * How many times each tenant has been invalidated on this node.
   *
   * <p>Grows only with identifiers an administrator has changed through this API, so it stays
   * small.
   */
  private final Map<String, Long> invalidations = new ConcurrentHashMap<>();

  /** Bumped by {@link #invalidateAll()}, so a lookup in flight across it cannot cache its read. */
  private final AtomicLong epoch = new AtomicLong();

  /** Default {@link #staleGrace}: five minutes. */
  static final Duration DEFAULT_STALE_GRACE = Duration.ofMinutes(5);

  @Autowired
  public TenantStatusLookupService(
      @Qualifier("hikariTenantDataSource") final DataSource tenantStoreDataSource,
      @Value("${fineract.tenant-management.status-cache-seconds:30}") final long cacheSeconds,
      @Value("${fineract.tenant-management.status-stale-grace-seconds:300}")
          final long staleGraceSeconds) {
    this(
        new JdbcTemplate(tenantStoreDataSource),
        Duration.ofSeconds(cacheSeconds),
        Duration.ofSeconds(staleGraceSeconds));
  }

  TenantStatusLookupService(final JdbcTemplate jdbcTemplate, final Duration timeToLive) {
    this(jdbcTemplate, timeToLive, DEFAULT_STALE_GRACE);
  }

  TenantStatusLookupService(
      final JdbcTemplate jdbcTemplate, final Duration timeToLive, final Duration staleGrace) {
    this.jdbcTemplate = jdbcTemplate;
    this.timeToLive = timeToLive;
    this.staleGrace = staleGrace;
  }

  /**
   * @param identifier tenant identifier as supplied by the caller
   * @return the answer, never null
   */
  public Lookup statusOf(final String identifier) {
    if (identifier == null || identifier.isBlank()) {
      return Lookup.noSuchTenant();
    }

    final CachedStatus cached = cache.get(identifier);
    if (cached != null && isFresh(cached)) {
      return cached.lookup();
    }

    // Snapshot the invalidation state before reading, and cache the read only if nothing
    // was invalidated while it was in flight. Without this a lookup could read ACTIVE,
    // lose the race to a suspension that commits and invalidates, and then cache that
    // stale ACTIVE - letting the suspended tenant through until the entry expires.
    final long epochBefore = epoch.get();
    final long generationBefore = invalidations.getOrDefault(identifier, 0L);

    final Lookup lookup = readStatus(identifier);

    if (lookup.kind() == Kind.REGISTRY_UNAVAILABLE) {
      // Stale-if-error, bounded. An expired status still counts for a while during a tenant
      // store outage, so a tenant last seen ACTIVE keeps working through a registry blip -
      // but only for staleGrace past its expiry, because another node may have suspended it
      // since. After that the unverifiable lookup is returned and refuses service. A status
      // that already refuses service stays refused however old it is; that is always safe.
      if (cached != null && (cached.lookup().refusesService() || isWithinStaleGrace(cached))) {
        return cached.lookup();
      }
      return lookup;
    }

    if (lookup.kind() == Kind.KNOWN || lookup.kind() == Kind.UNRECOGNISED) {
      // compute() is serialised with invalidate()'s remove() on the same key, and the
      // generation is bumped before that remove, so a concurrent invalidation is always
      // seen here - either before the put (skipped) or after it (removed).
      cache.compute(
          identifier,
          (key, existing) ->
              epoch.get() == epochBefore && invalidations.getOrDefault(key, 0L) == generationBefore
                  ? new CachedStatus(lookup, Instant.now())
                  : existing);
      evictIfOversized();
    }
    return lookup;
  }

  private Lookup readStatus(final String identifier) {
    final List<String> rows;
    try {
      rows =
          jdbcTemplate.queryForList(
              "select status from tenants where identifier = ?", String.class, identifier);
    } catch (final RuntimeException e) {
      // Not answered here: statusOf falls back to the last status it saw for this tenant,
      // and refuses when it has none.
      log.warn("Could not read status for tenant [{}] from the tenant store", identifier, e);
      return Lookup.registryUnavailable();
    }

    if (rows.isEmpty()) {
      return Lookup.noSuchTenant();
    }

    return TenantStatus.fromString(rows.get(0))
        .map(Lookup::known)
        .orElseGet(
            () -> {
              log.warn(
                  "Tenant [{}] has an unrecognised status; refusing its requests until corrected",
                  identifier);
              return Lookup.unrecognised();
            });
  }

  private boolean isFresh(final CachedStatus entry) {
    return Instant.now().isBefore(entry.readAt().plus(timeToLive));
  }

  private boolean isWithinStaleGrace(final CachedStatus entry) {
    return Instant.now().isBefore(entry.readAt().plus(timeToLive).plus(staleGrace));
  }

  /**
   * Keeps the cache inside {@link #MAX_CACHED_TENANTS}.
   *
   * <p>Expired entries are not removed on read, so they are swept here first. Clearing everything
   * is the last resort: it costs one query per tenant on the next requests, which is harmless,
   * whereas an unbounded map is not.
   */
  private void evictIfOversized() {
    if (cache.size() <= MAX_CACHED_TENANTS) {
      return;
    }
    cache.values().removeIf(entry -> !isFresh(entry));
    if (cache.size() > MAX_CACHED_TENANTS) {
      cache.clear();
    }
  }

  /** Drops one tenant's cached status, so a change made here takes effect immediately. */
  public void invalidate(final String identifier) {
    if (identifier == null) {
      return;
    }
    // Generation first, then removal: the order statusOf relies on.
    invalidations.merge(identifier, 1L, Long::sum);
    cache.remove(identifier);
  }

  /** Drops everything. Used by tests and available for operational recovery. */
  public void invalidateAll() {
    epoch.incrementAndGet();
    cache.clear();
  }
}
