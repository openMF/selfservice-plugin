/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.tenant;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import javax.sql.DataSource;
import liquibase.integration.spring.SpringLiquibase;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.infrastructure.core.service.Page;
import org.apache.fineract.infrastructure.core.service.database.DatabasePasswordEncryptor;
import org.apache.fineract.tenant.data.TenantCreateRequest;
import org.apache.fineract.tenant.data.TenantData;
import org.apache.fineract.tenant.data.TenantUpdateRequest;
import org.apache.fineract.tenant.domain.TenantStatus;
import org.apache.fineract.tenant.exception.TenantIdentifierAlreadyExistsException;
import org.apache.fineract.tenant.exception.TenantNotFoundException;
import org.apache.fineract.tenant.exception.TenantSchemaUnavailableException;
import org.apache.fineract.tenant.security.TenantMasterAccess;
import org.apache.fineract.tenant.security.TenantMasterUserBootstrap;
import org.apache.fineract.tenant.security.TenantMasterUserStore;
import org.apache.fineract.tenant.service.TenantAdministrationAuditService;
import org.apache.fineract.tenant.service.TenantManagementReadService;
import org.apache.fineract.tenant.service.TenantManagementWriteService;
import org.apache.fineract.tenant.service.TenantProvisioningService;
import org.apache.fineract.tenant.service.TenantSchemaMigrationService;
import org.apache.fineract.tenant.service.TenantStatusLookupService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Exercises tenant administration against a real PostgreSQL tenant store.
 *
 * <p>Unit tests cover the rules; this covers the things only a database can answer - that the
 * Liquibase changesets actually apply, that the SQL is valid PostgreSQL, that the unique constraint
 * behaves, and that reads see what writes produced.
 *
 * <p><strong>Scope.</strong> Only the tenant store is real. Schema provisioning and migration are
 * stubbed, because exercising those means standing up a second database and a whole Fineract; they
 * are covered by {@code SelfServiceIntegrationTestBase}-style tests that boot the platform. The
 * password encryptor is stubbed too, so a failure here points at this feature's SQL rather than at
 * core's cryptography.
 */
@Testcontainers
class TenantManagementPostgresIntegrationTest {

  private static PostgreSQLContainer<?> postgres;
  private static HikariDataSource dataSource;

  private TenantManagementReadService readService;
  private TenantManagementWriteService writeService;
  private JdbcTemplate jdbcTemplate;

  @BeforeAll
  static void startDatabase() throws Exception {
    postgres =
        new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("fineract_tenants")
            .withUsername("postgres")
            .withPassword("postgres");
    postgres.start();

    final HikariConfig config = new HikariConfig();
    config.setJdbcUrl(postgres.getJdbcUrl());
    config.setUsername(postgres.getUsername());
    config.setPassword(postgres.getPassword());
    config.setMaximumPoolSize(4);
    dataSource = new HikariDataSource(config);

    applyRegistryBaseline(dataSource);
    applyTenantManagementChangelog(dataSource);
  }

  /**
   * Builds the registry the way Fineract ships it.
   *
   * <p>Runs core's own initial-switch changelog straight out of the {@code fineract-provider} jar,
   * so the {@code tenants} and {@code tenant_server_connections} tables under test are genuinely
   * core's and not a hand-written approximation that could drift.
   */
  private static void applyRegistryBaseline(final DataSource dataSource) throws Exception {
    final SpringLiquibase liquibase = new SpringLiquibase();
    liquibase.setDataSource(dataSource);
    liquibase.setChangeLog(
        "classpath:/db/changelog/tenant-store/initial-switch-changelog-tenant-store.xml");
    liquibase.setContexts("initial_switch,tenant_store_db");
    // Core's initial-data changeset seeds the 'default' tenant from these placeholders,
    // which the platform normally supplies from fineract.tenant.* configuration. Left
    // unset they are inserted literally and overflow the column.
    liquibase.setChangeLogParameters(
        Map.ofEntries(
            Map.entry("fineract.tenant.identifier", "default"),
            Map.entry("fineract.tenant.description", "Default Demo Tenant"),
            Map.entry("fineract.tenant.timezone", "Asia/Kolkata"),
            Map.entry("fineract.tenant.host", "localhost"),
            Map.entry("fineract.tenant.port", "5432"),
            Map.entry("fineract.tenant.schema-name", "fineract_default"),
            Map.entry("fineract.tenant.username", "postgres"),
            Map.entry("fineract.tenant.password", "postgres"),
            Map.entry("fineract.tenant.parameters", "")));
    liquibase.setShouldRun(true);
    liquibase.afterPropertiesSet();

    // Core seeds the default tenant with explicit ids, which leaves PostgreSQL's
    // identity sequences pointing at 1. Core corrects that in part 0003; without it
    // the first generated id collides with the seeded row. Run separately because the
    // master changelog that normally carries it also pulls in Spring-injected
    // customChange tasks (parts 0007-0009) that cannot run standalone.
    final SpringLiquibase sequences = new SpringLiquibase();
    sequences.setDataSource(dataSource);
    sequences.setChangeLog(
        "classpath:/db/changelog/tenant-store/parts/0003_reset_postgresql_sequences.xml");
    sequences.setContexts("postgresql,tenant_store_db");
    sequences.setShouldRun(true);
    sequences.afterPropertiesSet();

    // Stands in for core changeset 0007, which adds this column. That changelog part
    // cannot run here: it also carries Spring-injected customChange tasks that encrypt
    // existing passwords, which need a Fineract application context. The column itself
    // is all this feature needs, and the write service sets it.
    new JdbcTemplate(dataSource)
        .execute(
            "alter table tenant_server_connections add column if not exists master_password_hash"
                + " varchar(255)");
  }

  /** Applies the changelog this feature adds - the thing actually under test. */
  private static void applyTenantManagementChangelog(final DataSource dataSource) throws Exception {
    final SpringLiquibase liquibase = new SpringLiquibase();
    liquibase.setDataSource(dataSource);
    liquibase.setChangeLog(
        "classpath:/db/changelog/tenantstore/module/tenantmanagement/module-changelog-master.xml");
    liquibase.setShouldRun(true);
    liquibase.afterPropertiesSet();
  }

  @AfterAll
  static void stopDatabase() {
    if (dataSource != null) {
      dataSource.close();
    }
    if (postgres != null) {
      postgres.stop();
    }
  }

  @BeforeEach
  void setUp() {
    jdbcTemplate = new JdbcTemplate(dataSource);
    // Start each test from a known registry. The baseline inserts a 'default' tenant.
    jdbcTemplate.update("delete from tenant_administration_audit");
    jdbcTemplate.update("delete from tenant_retained_schema");
    jdbcTemplate.update("delete from tenants where identifier <> 'default'");
    jdbcTemplate.update(
        "delete from tenant_server_connections where id not in (select oltp_id from tenants)");

    final DatabasePasswordEncryptor encryptor = mock(DatabasePasswordEncryptor.class);
    // Identity "encryption": this test is about the SQL, not core's cipher.
    when(encryptor.encrypt(anyString())).thenAnswer(i -> "enc:" + i.getArgument(0));
    when(encryptor.getMasterPasswordHash()).thenReturn("test-master-hash");

    readService = new TenantManagementReadService(dataSource);

    final TransactionTemplate transactionTemplate =
        new TransactionTemplate(new DataSourceTransactionManager(dataSource));

    @SuppressWarnings("unchecked")
    final ObjectProvider<org.springframework.cache.CacheManager> noCacheManager =
        mock(ObjectProvider.class);

    writeService =
        new TenantManagementWriteService(
            dataSource,
            transactionTemplate,
            encryptor,
            mock(TenantProvisioningService.class),
            readService,
            mock(TenantStatusLookupService.class),
            mock(TenantSchemaMigrationService.class),
            new TenantAdministrationAuditService(dataSource),
            noCacheManager,
            // Migration is stubbed, so creating must not try to run it.
            false);
  }

  private static TenantCreateRequest requestFor(final String identifier) {
    return new TenantCreateRequest(
        identifier,
        "Acme Microfinance",
        "Asia/Kolkata",
        TenantStatus.ACTIVE,
        "a description",
        "ops@example.org",
        "mifostenant_" + identifier,
        "db.example.org",
        "5432",
        "fineract",
        "s3cret",
        null,
        true);
  }

  // ---------------------------------------------------------------
  // The changesets themselves
  // ---------------------------------------------------------------

  @Test
  void theStatusColumnIsAddedAndExistingTenantsDefaultToActive() {
    // The migration must not change what an existing installation means: every
    // tenant already in the registry is by definition live.
    final String status =
        jdbcTemplate.queryForObject(
            "select status from tenants where identifier = 'default'", String.class);

    assertEquals("ACTIVE", status);
  }

  @Test
  void theAuditTableIsCreated() {
    assertEquals(
        0,
        jdbcTemplate.queryForObject(
            "select count(*) from tenant_administration_audit", Integer.class));
  }

  // ---------------------------------------------------------------
  // Create
  // ---------------------------------------------------------------

  @Test
  void createWritesBothRowsAndReadsBack() {
    final TenantData created = writeService.create(requestFor("acme"));

    assertNotNull(created.id());
    assertEquals("acme", created.identifier());
    assertEquals(TenantStatus.ACTIVE, created.status());
    assertEquals("Asia/Kolkata", created.timezoneId());
    assertEquals("ops@example.org", created.contactEmail());
    assertNotNull(created.connection());
    assertEquals("mifostenant_acme", created.connection().schemaName());
    // Populated so a tenant created through the API is not the only one in the
    // registry with a blank joined date.
    assertNotNull(created.joinedDate());
    assertNotNull(created.createdDate());
  }

  @Test
  void createStoresTheEncryptedPasswordAndTheMasterHash() {
    // Without the master hash, core's TenantDataSourceFactory refuses to open the
    // tenant at all - failing later, at startup, with "Invalid master password".
    writeService.create(requestFor("acme"));

    final var row =
        jdbcTemplate.queryForMap(
            "select ts.schema_password, ts.master_password_hash from tenants t"
                + " join tenant_server_connections ts on t.oltp_id = ts.id"
                + " where t.identifier = 'acme'");

    assertEquals("enc:s3cret", row.get("schema_password"));
    assertEquals("test-master-hash", row.get("master_password_hash"));
  }

  @Test
  void theStoredPasswordIsNeverReturnedByAnyRead() {
    writeService.create(requestFor("acme"));

    final TenantData read = readService.retrieveOne(writeService.create(requestFor("beta")).id());
    final Page<TenantData> listed = readService.retrieveAll(null, null, null, null);

    // The projection has no password column at all, so there is nothing to leak.
    assertFalse(read.toString().contains("s3cret"));
    assertFalse(listed.getPageItems().toString().contains("s3cret"));
    assertFalse(listed.getPageItems().toString().contains("enc:"));
  }

  @Test
  void theIdentifierMustBeUnique() {
    writeService.create(requestFor("acme"));

    assertThrows(
        TenantIdentifierAlreadyExistsException.class,
        () -> writeService.create(requestFor("acme")));
  }

  @Test
  void createIsRecordedInTheAuditTrailWithoutTheCredential() {
    writeService.create(requestFor("acme"));

    final var audit =
        jdbcTemplate.queryForMap(
            "select action, outcome, tenant_identifier, detail from tenant_administration_audit"
                + " where tenant_identifier = 'acme'");

    assertEquals("CREATE", audit.get("action"));
    assertEquals("SUCCESS", audit.get("outcome"));
    assertFalse(String.valueOf(audit.get("detail")).contains("s3cret"));
  }

  // ---------------------------------------------------------------
  // Read: search, filter, paging
  // ---------------------------------------------------------------

  @Test
  void listingFiltersByStatus() {
    final TenantData acme = writeService.create(requestFor("acme"));
    writeService.create(requestFor("beta"));
    writeService.changeStatus(acme.id(), TenantStatus.SUSPENDED);

    final Page<TenantData> suspended =
        readService.retrieveAll(null, TenantStatus.SUSPENDED, null, null);

    assertEquals(1, suspended.getTotalFilteredRecords());
    assertEquals("acme", suspended.getPageItems().get(0).identifier());
  }

  @Test
  void searchMatchesIdentifierAndNameCaseInsensitively() {
    writeService.create(requestFor("acme"));

    assertEquals(1, readService.retrieveAll("ACME", null, null, null).getTotalFilteredRecords());
    assertEquals(
        1, readService.retrieveAll("microfinance", null, null, null).getTotalFilteredRecords());
    assertEquals(
        0, readService.retrieveAll("nothing-matches", null, null, null).getTotalFilteredRecords());
  }

  @Test
  void aSearchTermContainingWildcardsIsMatchedLiterally() {
    // Bound as a parameter, so % does not become "match everything".
    writeService.create(requestFor("acme"));

    assertEquals(0, readService.retrieveAll("%", null, null, null).getTotalFilteredRecords());
  }

  @Test
  void pagingReturnsAPageAndTheUnpagedTotal() {
    writeService.create(requestFor("acme"));
    writeService.create(requestFor("beta"));
    writeService.create(requestFor("gamma"));

    final Page<TenantData> firstPage = readService.retrieveAll(null, null, 0, 2);

    assertEquals(2, firstPage.getPageItems().size());
    // 3 created plus the baseline 'default' tenant.
    assertEquals(4, firstPage.getTotalFilteredRecords());
  }

  @Test
  void retrievingAnUnknownTenantIsReportedAsNotFound() {
    assertThrows(TenantNotFoundException.class, () -> readService.retrieveOne(999_999L));
  }

  @Test
  void theTemplateOffersTimezonesAndStatuses() {
    final var template = readService.retrieveTemplate();

    assertFalse(template.timezones().isEmpty());
    assertEquals(List.of("ACTIVE", "INACTIVE", "SUSPENDED"), template.statuses());
  }

  // ---------------------------------------------------------------
  // Update
  // ---------------------------------------------------------------

  @Test
  void updateChangesOnlyWhatWasSupplied() {
    final TenantData created = writeService.create(requestFor("acme"));

    final TenantData updated =
        writeService.update(
            created.id(),
            new TenantUpdateRequest(
                "Renamed", null, null, null, null, null, null, null, null, null));

    assertEquals("Renamed", updated.name());
    // Untouched fields survive.
    assertEquals("Asia/Kolkata", updated.timezoneId());
    assertEquals("ops@example.org", updated.contactEmail());
    assertEquals("db.example.org", updated.connection().schemaServer());
  }

  @Test
  void omittingThePasswordKeepsTheStoredOne() {
    final TenantData created = writeService.create(requestFor("acme"));

    writeService.update(
        created.id(),
        new TenantUpdateRequest("Renamed", null, null, null, null, null, null, null, null, null));

    assertEquals(
        "enc:s3cret",
        jdbcTemplate.queryForObject(
            "select ts.schema_password from tenants t join tenant_server_connections ts"
                + " on t.oltp_id = ts.id where t.identifier = 'acme'",
            String.class));
  }

  @Test
  void rotatingThePasswordReEncryptsItAndReStampsTheMasterHash() {
    final TenantData created = writeService.create(requestFor("acme"));

    writeService.update(
        created.id(),
        new TenantUpdateRequest(null, null, null, null, null, null, null, "rotated", null, null));

    final var row =
        jdbcTemplate.queryForMap(
            "select ts.schema_password, ts.master_password_hash from tenants t"
                + " join tenant_server_connections ts on t.oltp_id = ts.id"
                + " where t.identifier = 'acme'");

    assertEquals("enc:rotated", row.get("schema_password"));
    assertEquals("test-master-hash", row.get("master_password_hash"));
  }

  @Test
  void anUpdateRecordsChangedFieldNamesButNotValues() {
    final TenantData created = writeService.create(requestFor("acme"));

    writeService.update(
        created.id(),
        new TenantUpdateRequest(null, null, null, null, null, null, null, "rotated", null, null));

    final String detail =
        jdbcTemplate.queryForObject(
            "select detail from tenant_administration_audit where action = 'UPDATE'", String.class);

    assertTrue(detail.contains("schemaPassword"));
    assertFalse(detail.contains("rotated"));
  }

  // ---------------------------------------------------------------
  // Status and removal
  // ---------------------------------------------------------------

  @Test
  void statusChangesArePersistedAndIdempotent() {
    final TenantData created = writeService.create(requestFor("acme"));

    assertEquals(
        TenantStatus.SUSPENDED,
        writeService.changeStatus(created.id(), TenantStatus.SUSPENDED).status());
    // Re-issuing the same command succeeds rather than erroring, so a retry does
    // not look like a failure.
    assertEquals(
        TenantStatus.SUSPENDED,
        writeService.changeStatus(created.id(), TenantStatus.SUSPENDED).status());
  }

  @Test
  void anActiveTenantCannotBeRemoved() {
    final TenantData created = writeService.create(requestFor("acme"));

    assertThrows(GeneralPlatformDomainRuleException.class, () -> writeService.delete(created.id()));
    assertNotNull(readService.retrieveOne(created.id()));
  }

  @Test
  void aDeactivatedTenantIsRemovedFromTheRegistry() {
    final TenantData created = writeService.create(requestFor("acme"));
    writeService.changeStatus(created.id(), TenantStatus.INACTIVE);

    writeService.delete(created.id());

    assertThrows(TenantNotFoundException.class, () -> readService.retrieveOne(created.id()));
    // The connection row goes too, so the registry keeps no orphan.
    assertEquals(
        0,
        jdbcTemplate.queryForObject(
            "select count(*) from tenant_server_connections where schema_name = 'mifostenant_acme'",
            Integer.class));
  }

  @Test
  void theAuditTrailOutlivesTheTenantItDescribes() {
    // An audit row that vanished with the tenant whose deletion it recorded would
    // be worthless, which is why the trail lives in the registry database and
    // carries the identifier rather than a foreign key.
    final TenantData created = writeService.create(requestFor("acme"));
    writeService.changeStatus(created.id(), TenantStatus.INACTIVE);
    writeService.delete(created.id());

    final var audit =
        jdbcTemplate.queryForMap(
            "select action, tenant_id from tenant_administration_audit where action = 'DELETE'");

    assertEquals("DELETE", audit.get("action"));
    // Null so the trail cannot point at an id another tenant may later reuse.
    assertNull(audit.get("tenant_id"));
  }

  @Test
  void everyMutationLeavesATrail() {
    final TenantData created = writeService.create(requestFor("acme"));
    writeService.update(
        created.id(),
        new TenantUpdateRequest("Renamed", null, null, null, null, null, null, null, null, null));
    writeService.changeStatus(created.id(), TenantStatus.SUSPENDED);
    writeService.changeStatus(created.id(), TenantStatus.INACTIVE);
    writeService.delete(created.id());

    final List<String> actions =
        jdbcTemplate.queryForList(
            "select action from tenant_administration_audit where tenant_identifier = 'acme'"
                + " order by id",
            String.class);

    assertEquals(List.of("CREATE", "UPDATE", "SUSPEND", "DEACTIVATE", "DELETE"), actions);
  }

  // ---------------------------------------------------------------
  // Cache eviction against several managers - reproduces a real-Fineract failure
  // ---------------------------------------------------------------

  private TenantManagementWriteService writeServiceWithCacheManagers(
      final CacheManager... managers) {
    final DatabasePasswordEncryptor encryptor = mock(DatabasePasswordEncryptor.class);
    when(encryptor.encrypt(anyString())).thenAnswer(i -> "enc:" + i.getArgument(0));
    when(encryptor.getMasterPasswordHash()).thenReturn("test-master-hash");

    @SuppressWarnings("unchecked")
    final ObjectProvider<CacheManager> provider = mock(ObjectProvider.class);
    when(provider.orderedStream()).thenAnswer(i -> Stream.of(managers));

    return new TenantManagementWriteService(
        dataSource,
        new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
        encryptor,
        mock(TenantProvisioningService.class),
        readService,
        mock(TenantStatusLookupService.class),
        mock(TenantSchemaMigrationService.class),
        new TenantAdministrationAuditService(dataSource),
        provider,
        false);
  }

  @Test
  void aChangeEvictsTheTenantFromEveryCacheManagerThatHoldsTheCache() {
    // A running Fineract registers four CacheManagers. Asking Spring for "the" one threw
    // NoUniqueBeanDefinitionException after create had committed, which stranded a
    // registered tenant with an empty schema. Eviction must reach every manager.
    final ConcurrentMapCacheManager first = new ConcurrentMapCacheManager("tenantsById");
    final ConcurrentMapCacheManager second = new ConcurrentMapCacheManager("tenantsById");
    final ConcurrentMapCacheManager unrelated = new ConcurrentMapCacheManager("somethingElse");
    final TenantManagementWriteService service =
        writeServiceWithCacheManagers(first, second, unrelated);

    final TenantData created = service.create(requestFor("acme"));
    first.getCache("tenantsById").put("acme", "stale");
    second.getCache("tenantsById").put("acme", "stale");

    service.changeStatus(created.id(), TenantStatus.SUSPENDED);

    assertNull(first.getCache("tenantsById").get("acme"));
    assertNull(second.getCache("tenantsById").get("acme"));
  }

  @Test
  void aFailingCacheManagerDoesNotFailACommittedChange() {
    // The registry write has already committed when eviction runs, so a cache problem
    // must never surface as a failed request or strand a half-created tenant.
    final CacheManager broken = mock(CacheManager.class);
    when(broken.getCache(anyString())).thenThrow(new IllegalStateException("cache down"));
    final TenantManagementWriteService service = writeServiceWithCacheManagers(broken);

    final TenantData created = service.create(requestFor("acme"));

    assertEquals(
        TenantStatus.SUSPENDED,
        service.changeStatus(created.id(), TenantStatus.SUSPENDED).status());
    assertEquals(
        1,
        jdbcTemplate.queryForObject(
            "select count(*) from tenant_administration_audit where action = 'CREATE'",
            Integer.class));
  }

  // ---------------------------------------------------------------
  // Review follow-ups against a real database
  // ---------------------------------------------------------------

  @Test
  void concurrentCreationOfTheSameSchemaNeverFails() throws Exception {
    // Two creates racing past the existence check make PostgreSQL reject one CREATE
    // DATABASE as a duplicate; the loser must treat the now-existing schema as success.
    // Threads do not guarantee the collision on every run, so several rounds are tried.
    // The assertion - no caller ever fails, and exactly one database results - holds
    // whether or not a given round actually collided.
    final TenantProvisioningService provisioning = new TenantProvisioningService(dataSource);
    final ExecutorService pool = Executors.newFixedThreadPool(4);
    try {
      for (int round = 0; round < 5; round++) {
        final String schema = "race_schema_" + round;
        final CountDownLatch start = new CountDownLatch(1);
        final List<Future<Object>> attempts = new ArrayList<>();
        for (int caller = 0; caller < 4; caller++) {
          attempts.add(
              pool.submit(
                  () -> {
                    start.await();
                    provisioning.createSchemaIfAbsent(
                        postgres.getHost(),
                        String.valueOf(postgres.getFirstMappedPort()),
                        schema,
                        null,
                        postgres.getUsername(),
                        postgres.getPassword());
                    return null;
                  }));
        }
        start.countDown();
        for (final Future<Object> attempt : attempts) {
          attempt.get(60, TimeUnit.SECONDS);
        }
        assertEquals(
            1,
            jdbcTemplate.queryForObject(
                "select count(*) from pg_database where datname = ?", Integer.class, schema));
      }
    } finally {
      pool.shutdownNow();
      for (int round = 0; round < 5; round++) {
        jdbcTemplate.execute("drop database if exists race_schema_" + round);
      }
    }
  }

  @Test
  void timestampsAreStoredAndReadAsUtcWhateverTheJvmTimeZone() {
    // Registry and audit timestamp columns carry no zone. Binding or reading them through
    // the JVM's default time zone stores and reports different instants on nodes
    // configured differently, so this writes under one zone and reads under another.
    final DateTimeFormatter wallClock = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    final TimeZone original = TimeZone.getDefault();
    try {
      TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"));
      final TenantData created = writeService.create(requestFor("acme"));

      TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
      final TenantData read = readService.retrieveOne(created.id());

      final OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
      assertTrue(
          Duration.between(read.createdDate(), now).abs().toMinutes() < 2,
          "createdDate " + read.createdDate() + " should be close to " + now);

      final String storedTenant =
          jdbcTemplate.queryForObject(
              "select to_char(created_date, 'YYYY-MM-DD HH24:MI:SS') from tenants"
                  + " where identifier = 'acme'",
              String.class);
      assertTrue(
          Duration.between(
                      LocalDateTime.parse(storedTenant, wallClock),
                      LocalDateTime.now(ZoneOffset.UTC))
                  .abs()
                  .toMinutes()
              < 2,
          "tenants.created_date " + storedTenant + " should be a UTC wall-clock value");

      final String storedAudit =
          jdbcTemplate.queryForObject(
              "select to_char(created_at, 'YYYY-MM-DD HH24:MI:SS') from tenant_administration_audit"
                  + " where tenant_identifier = 'acme' and action = 'CREATE'",
              String.class);
      assertTrue(
          Duration.between(
                      LocalDateTime.parse(storedAudit, wallClock),
                      LocalDateTime.now(ZoneOffset.UTC))
                  .abs()
                  .toMinutes()
              < 2,
          "audit created_at " + storedAudit + " should be a UTC wall-clock value");
    } finally {
      TimeZone.setDefault(original);
    }
  }

  // ---------------------------------------------------------------
  // Master users - the super master context
  // ---------------------------------------------------------------

  @Test
  void aBootstrappedMasterUserIsStoredHashedAndCreatedOnlyOnce() {
    jdbcTemplate.update("delete from tenant_master_user");
    final TenantMasterUserStore store = new TenantMasterUserStore(dataSource);

    new TenantMasterUserBootstrap(store, "master", "a-long-enough-password").afterPropertiesSet();
    // A changed configured password must not silently reset an existing master user.
    new TenantMasterUserBootstrap(store, "master", "a-different-long-password")
        .afterPropertiesSet();

    assertEquals(1, store.count());
    final TenantMasterUserStore.MasterUser user = store.findByUsername("master").orElseThrow();
    assertEquals("SUPER_MASTER", user.role());
    assertTrue(user.enabled());
    assertFalse(user.passwordHash().contains("a-long-enough-password"));
    assertTrue(
        PasswordEncoderFactories.createDelegatingPasswordEncoder()
            .matches("a-long-enough-password", user.passwordHash()));
  }

  @Test
  void aBootstrapPasswordThatIsTooShortCreatesNoMasterUser() {
    jdbcTemplate.update("delete from tenant_master_user");
    final TenantMasterUserStore store = new TenantMasterUserStore(dataSource);

    new TenantMasterUserBootstrap(store, "master", "short").afterPropertiesSet();

    assertEquals(0, store.count());
  }

  @Test
  void noBootstrapConfigurationCreatesNoMasterUser() {
    jdbcTemplate.update("delete from tenant_master_user");
    final TenantMasterUserStore store = new TenantMasterUserStore(dataSource);

    new TenantMasterUserBootstrap(store, "", "").afterPropertiesSet();

    assertEquals(0, store.count());
    assertTrue(store.findByUsername("master").isEmpty());
  }

  // ---------------------------------------------------------------
  // Review round 2: who a database belongs to, and clearing optional fields
  // ---------------------------------------------------------------

  private static TenantCreateRequest requestFor(final String identifier, final String schemaName) {
    return new TenantCreateRequest(
        identifier,
        "Another Microfinance",
        "Asia/Kolkata",
        TenantStatus.ACTIVE,
        null,
        null,
        schemaName,
        "db.example.org",
        "5432",
        "fineract",
        "s3cret",
        null,
        true);
  }

  @Test
  void aDatabaseAlreadyUsedByAnotherTenantIsRefused() {
    writeService.create(requestFor("acme"));

    assertThrows(
        TenantSchemaUnavailableException.class,
        () -> writeService.create(requestFor("intruder", "mifostenant_acme")));
    assertEquals(
        0,
        jdbcTemplate.queryForObject(
            "select count(*) from tenants where identifier = 'intruder'", Integer.class));
  }

  @Test
  void databaseNamesAreMatchedIgnoringCase() {
    // PostgreSQL folds an unquoted CREATE DATABASE name to lower case, so these are the
    // same database.
    writeService.create(requestFor("acme"));

    assertThrows(
        TenantSchemaUnavailableException.class,
        () -> writeService.create(requestFor("intruder", "MIFOSTENANT_ACME")));
  }

  @Test
  void aRemovedTenantsRetainedDatabaseCannotBeClaimedByAnotherIdentifier() {
    final TenantData acme = writeService.create(requestFor("acme"));
    writeService.changeStatus(acme.id(), TenantStatus.INACTIVE);
    writeService.delete(acme.id());

    assertThrows(
        TenantSchemaUnavailableException.class,
        () -> writeService.create(requestFor("intruder", "mifostenant_acme")));
  }

  @Test
  void aRemovedTenantCanBeReinstatedUnderItsOwnIdentifier() {
    final TenantData acme = writeService.create(requestFor("acme"));
    writeService.changeStatus(acme.id(), TenantStatus.INACTIVE);
    writeService.delete(acme.id());

    final TenantData reinstated = writeService.create(requestFor("acme"));

    assertEquals("acme", reinstated.identifier());
    assertEquals(
        0,
        jdbcTemplate.queryForObject(
            "select count(*) from tenant_retained_schema where tenant_identifier = 'acme'",
            Integer.class));
  }

  @Test
  void theTenantStoresOwnDatabaseIsRefused() {
    assertThrows(
        TenantSchemaUnavailableException.class,
        () -> writeService.create(requestFor("store", postgres.getDatabaseName())));
  }

  @Test
  void anUpdateCanClearAnOptionalField() {
    final TenantData created = writeService.create(requestFor("acme"));

    writeService.update(
        created.id(),
        new TenantUpdateRequest(null, null, "", "", null, null, null, null, null, null));

    final TenantData read = readService.retrieveOne(created.id());
    assertNull(read.description());
    assertNull(read.contactEmail());
    assertEquals("Acme Microfinance", read.name());
  }

  // ---------------------------------------------------------------
  // Review round 3: unrecognised status, delete race, bootstrap race
  // ---------------------------------------------------------------

  private TenantManagementWriteService writeServiceReading(
      final TenantManagementReadService reads) {
    final DatabasePasswordEncryptor encryptor = mock(DatabasePasswordEncryptor.class);
    when(encryptor.encrypt(anyString())).thenAnswer(i -> "enc:" + i.getArgument(0));
    when(encryptor.getMasterPasswordHash()).thenReturn("test-master-hash");

    @SuppressWarnings("unchecked")
    final ObjectProvider<CacheManager> noCacheManagers = mock(ObjectProvider.class);
    when(noCacheManagers.orderedStream()).thenAnswer(i -> Stream.empty());

    return new TenantManagementWriteService(
        dataSource,
        new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
        encryptor,
        mock(TenantProvisioningService.class),
        reads,
        mock(TenantStatusLookupService.class),
        mock(TenantSchemaMigrationService.class),
        new TenantAdministrationAuditService(dataSource),
        noCacheManagers,
        false);
  }

  @Test
  void anUnrecognisedStoredStatusIsReportedAsNullNotActive() {
    // The status filter refuses such a tenant; showing it as ACTIVE would tell
    // administrators the opposite of what the platform does.
    final TenantData created = writeService.create(requestFor("acme"));
    jdbcTemplate.update("update tenants set status = 'DELETED' where id = ?", created.id());

    assertNull(readService.retrieveOne(created.id()).status());
    // ...and it can still be corrected through the API.
    assertEquals(
        TenantStatus.ACTIVE, writeService.changeStatus(created.id(), TenantStatus.ACTIVE).status());
  }

  @Test
  void aTenantActivatedAfterTheDeleteCheckIsNotRemoved() {
    final TenantData created = writeService.create(requestFor("acme"));
    writeService.changeStatus(created.id(), TenantStatus.INACTIVE);
    final TenantData seenInactive = readService.retrieveOne(created.id());

    // A concurrent activation commits after delete() has already read the tenant as inactive.
    jdbcTemplate.update("update tenants set status = 'ACTIVE' where id = ?", created.id());
    final TenantManagementReadService staleRead = spy(readService);
    doReturn(seenInactive).when(staleRead).retrieveOne(created.id());

    assertThrows(
        GeneralPlatformDomainRuleException.class,
        () -> writeServiceReading(staleRead).delete(created.id()));
    assertEquals(
        1,
        jdbcTemplate.queryForObject(
            "select count(*) from tenants where id = ?", Integer.class, created.id()));
    assertEquals(
        0,
        jdbcTemplate.queryForObject(
            "select count(*) from tenant_retained_schema where tenant_identifier = 'acme'",
            Integer.class));
  }

  @Test
  void losingTheFirstDeploymentRaceForTheMasterUserDoesNotFailStartup() {
    jdbcTemplate.update("delete from tenant_master_user");
    final TenantMasterUserStore store = new TenantMasterUserStore(dataSource);
    // Another node inserts the user after this node has found it absent.
    final TenantMasterUserStore racing =
        new TenantMasterUserStore(dataSource) {
          private boolean firstLookup = true;

          @Override
          public Optional<TenantMasterUserStore.MasterUser> findByUsername(final String username) {
            if (firstLookup) {
              firstLookup = false;
              store.create(username, "{noop}other-node", TenantMasterAccess.SUPER_MASTER_ROLE);
              return Optional.empty();
            }
            return super.findByUsername(username);
          }
        };

    assertDoesNotThrow(
        () ->
            new TenantMasterUserBootstrap(racing, "master", "a-long-enough-password")
                .afterPropertiesSet());
    assertEquals(1, store.count());
  }
}
