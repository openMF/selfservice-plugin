/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.tenant.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.fineract.tenant.domain.TenantStatus;
import org.apache.fineract.tenant.service.TenantStatusLookupService.Kind;
import org.apache.fineract.tenant.service.TenantStatusLookupService.Lookup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

class TenantStatusLookupServiceTest {

  private JdbcTemplate jdbcTemplate;
  private TenantStatusLookupService service;

  @BeforeEach
  void setUp() {
    jdbcTemplate = mock(JdbcTemplate.class);
    service = new TenantStatusLookupService(jdbcTemplate, Duration.ofMinutes(5));
  }

  private void registryHolds(final String identifier, final List<String> rows) {
    when(jdbcTemplate.queryForList(anyString(), eq(String.class), eq(identifier))).thenReturn(rows);
  }

  private void verifyReads(final String identifier, final int times) {
    verify(jdbcTemplate, times(times)).queryForList(anyString(), eq(String.class), eq(identifier));
  }

  @Test
  void aKnownStatusIsCached() {
    registryHolds("acme", List.of("ACTIVE"));

    final Lookup first = service.statusOf("acme");
    service.statusOf("acme");

    assertEquals(Kind.KNOWN, first.kind());
    assertEquals(TenantStatus.ACTIVE, first.status());
    assertFalse(first.refusesService());
    verifyReads("acme", 1);
  }

  @Test
  void aSuspendedTenantRefusesService() {
    registryHolds("acme", List.of("SUSPENDED"));

    assertTrue(service.statusOf("acme").refusesService());
  }

  @Test
  void aNonexistentTenantIsNeverCached() {
    // The identifier comes from a request header. Caching "no such tenant" would let
    // anyone grow the cache without bound by inventing identifiers.
    registryHolds("invented", List.of());

    final Lookup first = service.statusOf("invented");
    service.statusOf("invented");

    assertEquals(Kind.NO_SUCH_TENANT, first.kind());
    assertFalse(first.refusesService());
    verifyReads("invented", 2);
  }

  @Test
  void anUnrecognisedStoredStatusFailsClosed() {
    registryHolds("acme", List.of("DELETED"));

    final Lookup lookup = service.statusOf("acme");

    assertEquals(Kind.UNRECOGNISED, lookup.kind());
    assertTrue(lookup.refusesService());
  }

  @Test
  void anUnreadableRegistryWithNoEarlierStatusRefusesService() {
    // Core keeps resolving tenants from its own cache while the tenant store is down, so
    // letting an unverifiable request through could serve a suspended tenant.
    when(jdbcTemplate.queryForList(anyString(), eq(String.class), eq("acme")))
        .thenThrow(new DataAccessResourceFailureException("tenant store down"));

    final Lookup first = service.statusOf("acme");
    service.statusOf("acme");

    assertEquals(Kind.REGISTRY_UNAVAILABLE, first.kind());
    assertTrue(first.refusesService());
    verifyReads("acme", 2);
  }

  @Test
  void anUnreadableRegistryFallsBackToTheLastKnownActiveStatus() {
    // An expired entry is still the last thing known; a registry blip must not take a
    // tenant last seen ACTIVE offline.
    final TenantStatusLookupService expiring =
        new TenantStatusLookupService(jdbcTemplate, Duration.ZERO);
    when(jdbcTemplate.queryForList(anyString(), eq(String.class), eq("acme")))
        .thenReturn(List.of("ACTIVE"))
        .thenThrow(new DataAccessResourceFailureException("tenant store down"));

    expiring.statusOf("acme");
    final Lookup duringOutage = expiring.statusOf("acme");

    assertEquals(TenantStatus.ACTIVE, duringOutage.status());
    assertFalse(duringOutage.refusesService());
  }

  @Test
  void anUnreadableRegistryKeepsASuspendedTenantRefused() {
    final TenantStatusLookupService expiring =
        new TenantStatusLookupService(jdbcTemplate, Duration.ZERO);
    when(jdbcTemplate.queryForList(anyString(), eq(String.class), eq("acme")))
        .thenReturn(List.of("SUSPENDED"))
        .thenThrow(new DataAccessResourceFailureException("tenant store down"));

    expiring.statusOf("acme");
    final Lookup duringOutage = expiring.statusOf("acme");

    assertEquals(TenantStatus.SUSPENDED, duringOutage.status());
    assertTrue(duringOutage.refusesService());
  }

  @Test
  void anInvalidationDuringTheReadIsNotOverwrittenByTheStaleValue() {
    // The race: a lookup reads ACTIVE, a suspension commits and invalidates while that
    // read is in flight, and the lookup then caches its stale ACTIVE - letting the
    // suspended tenant through until the entry expires.
    final AtomicInteger reads = new AtomicInteger();
    when(jdbcTemplate.queryForList(anyString(), eq(String.class), eq("acme")))
        .thenAnswer(
            invocation -> {
              if (reads.getAndIncrement() == 0) {
                service.invalidate("acme");
                return List.of("ACTIVE");
              }
              return List.of("SUSPENDED");
            });

    assertEquals(TenantStatus.ACTIVE, service.statusOf("acme").status());
    assertEquals(TenantStatus.SUSPENDED, service.statusOf("acme").status());
    verifyReads("acme", 2);
  }

  @Test
  void anInvalidateAllDuringTheReadIsNotOverwrittenByTheStaleValue() {
    final AtomicInteger reads = new AtomicInteger();
    when(jdbcTemplate.queryForList(anyString(), eq(String.class), eq("acme")))
        .thenAnswer(
            invocation -> {
              if (reads.getAndIncrement() == 0) {
                service.invalidateAll();
                return List.of("ACTIVE");
              }
              return List.of("SUSPENDED");
            });

    service.statusOf("acme");

    assertEquals(TenantStatus.SUSPENDED, service.statusOf("acme").status());
  }

  @Test
  void invalidatingATenantForcesAFreshRead() {
    registryHolds("acme", List.of("ACTIVE"));

    service.statusOf("acme");
    service.invalidate("acme");
    service.statusOf("acme");

    verifyReads("acme", 2);
  }

  @Test
  void anExpiredEntryIsReadAgain() {
    final TenantStatusLookupService expiring =
        new TenantStatusLookupService(jdbcTemplate, Duration.ZERO);
    registryHolds("acme", List.of("ACTIVE"));

    expiring.statusOf("acme");
    expiring.statusOf("acme");

    verifyReads("acme", 2);
  }

  @Test
  void aBlankIdentifierNeverReachesTheRegistry() {
    assertEquals(Kind.NO_SUCH_TENANT, service.statusOf("  ").kind());
    verify(jdbcTemplate, never()).queryForList(anyString(), eq(String.class), anyString());
  }

  @Test
  void anExpiredActiveStatusIsNotTrustedPastTheStaleGrace() {
    // Another node may have suspended the tenant since. Past the grace, an unverifiable
    // ACTIVE refuses service instead of being trusted for the whole outage.
    final TenantStatusLookupService noGrace =
        new TenantStatusLookupService(jdbcTemplate, Duration.ZERO, Duration.ZERO);
    when(jdbcTemplate.queryForList(anyString(), eq(String.class), eq("acme")))
        .thenReturn(List.of("ACTIVE"))
        .thenThrow(new DataAccessResourceFailureException("tenant store down"));

    noGrace.statusOf("acme");
    final Lookup duringLongOutage = noGrace.statusOf("acme");

    assertEquals(Kind.REGISTRY_UNAVAILABLE, duringLongOutage.kind());
    assertTrue(duringLongOutage.refusesService());
  }

  @Test
  void anExpiredSuspendedStatusStaysRefusedPastTheStaleGrace() {
    final TenantStatusLookupService noGrace =
        new TenantStatusLookupService(jdbcTemplate, Duration.ZERO, Duration.ZERO);
    when(jdbcTemplate.queryForList(anyString(), eq(String.class), eq("acme")))
        .thenReturn(List.of("SUSPENDED"))
        .thenThrow(new DataAccessResourceFailureException("tenant store down"));

    noGrace.statusOf("acme");
    final Lookup duringLongOutage = noGrace.statusOf("acme");

    assertEquals(TenantStatus.SUSPENDED, duringLongOutage.status());
    assertTrue(duringLongOutage.refusesService());
  }

  @Test
  void theDefaultStaleGraceIsFiveMinutes() {
    assertEquals(Duration.ofMinutes(5), TenantStatusLookupService.DEFAULT_STALE_GRACE);
  }
}
