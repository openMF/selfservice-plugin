/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.testing.support;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

public abstract class SelfServiceIntegrationTestBase {

  private static final Logger LOG = LoggerFactory.getLogger(SelfServiceIntegrationTestBase.class);

  private static final Network network = Network.newNetwork();

  protected static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:15-alpine")
          .withNetwork(network)
          .withNetworkAliases("db")
          .withDatabaseName("fineract_default")
          .withUsername("postgres")
          .withPassword("postgres");

  protected static final GenericContainer<?> fineract;

  static {
    postgres.start();

    try {
      postgres.execInContainer("psql", "-U", "postgres", "-c", "CREATE DATABASE fineract_tenants;");
    } catch (Exception e) {
      throw new RuntimeException(
          "Failed to initialize fineract_tenants database in Testcontainers", e);
    }

    final String fromEnv = System.getenv("FINERACT_IT_IMAGE");
    final String fromProperty = System.getProperty("fineract.it.image");
    final String defaultImage = "apache/fineract:develop";
    final String fineractImage =
        fromProperty != null && !fromProperty.isBlank()
            ? fromProperty
            : (fromEnv != null && !fromEnv.isBlank() ? fromEnv : defaultImage);
    final DockerImageName dockerImageName =
        DockerImageName.parse(fineractImage).asCompatibleSubstituteFor("apache/fineract");

    String buildDir = System.getProperty("project.build.directory", "target");
    String testPluginsPath = buildDir + "/test-plugins";
    java.io.File testPluginsDir = new java.io.File(testPluginsPath);
    if (!testPluginsDir.exists() && !testPluginsDir.mkdirs()) {
      throw new IllegalStateException("Failed to create test-plugins directory at: " + testPluginsPath);
    }
    if (!testPluginsDir.isDirectory()) {
      throw new IllegalStateException("Path exists but is not a directory: " + testPluginsPath);
    }

    fineract =
        new GenericContainer<>(dockerImageName)
            .withNetwork(network)
            .withExposedPorts(8443)
            .withEnv("FINERACT_HIKARI_JDBC_URL", "jdbc:postgresql://db:5432/fineract_tenants")
            .withEnv("FINERACT_HIKARI_USERNAME", "postgres")
            .withEnv("FINERACT_HIKARI_PASSWORD", "postgres")
            .withEnv("FINERACT_HIKARI_DRIVER_SOURCE_CLASS_NAME", "org.postgresql.Driver")
            .withEnv("FINERACT_DEFAULT_TENANTDB_HOSTNAME", "db")
            .withEnv("FINERACT_DEFAULT_TENANTDB_PORT", "5432")
            .withEnv("FINERACT_DEFAULT_TENANTDB_UID", "postgres")
            .withEnv("FINERACT_DEFAULT_TENANTDB_PWD", "postgres")
            .withEnv("FINERACT_DEFAULT_TENANTDB_CONN_PARAMS", "")
            .withEnv("FINERACT_MODULE_SELFSERVICE_ENABLED", "true")
            .withEnv("SPRING_MAIN_ALLOW_BEAN_DEFINITION_OVERRIDING", "true")
            .withEnv("FINERACT_MODULES_SELFSERVICE_RUNREPORTS_ALLOWLIST", "Client Details")
            .withEnv("TZ", "UTC")
            .withEnv("JAVA_TOOL_OPTIONS", "-Xmx2G")
            .withEnv("FINERACT_SERVER_SSL_ENABLED", "true")
            .withEnv("FINERACT_SERVER_PORT", "8443")
            .withCopyFileToContainer(
                MountableFile.forHostPath(buildDir + "/selfservice-plugin-1.15.0-SNAPSHOT.jar"),
                "/app/plugins/selfservice-plugin.jar")
            .withCopyFileToContainer(
                MountableFile.forHostPath(testPluginsPath + "/"), "/app/test-plugins/")
            .withCreateContainerCmdModifier(
                cmd -> {
                  cmd.withEntrypoint(
                      "sh",
                      "-c",
                      "CLASSPATH=$(cat /app/jib-classpath-file) && "
                          + "exec java $JAVA_TOOL_OPTIONS "
                          + "-Duser.home=/tmp -Dfile.encoding=UTF-8 -Duser.timezone=UTC -Djava.security.egd=file:/dev/./urandom "
                          + "-cp /app/plugins/selfservice-plugin.jar:/app/test-plugins/*:$CLASSPATH "
                          + "org.apache.fineract.ServerApplication");
                  cmd.withCmd();
                })
            .withLogConsumer(new Slf4jLogConsumer(LOG).withPrefix("fineract"))
            .waitingFor(
                Wait.forHttps("/fineract-provider/actuator/health")
                    .allowInsecure()
                    .forStatusCode(200)
                    .withStartupTimeout(Duration.ofMinutes(15)));

    try {
      fineract.start();
    } catch (Exception e) {
      String containerLogs = "";
      try {
        containerLogs = fineract.getLogs();
      } catch (Exception logEx) {
        containerLogs = "Failed to retrieve container logs: " + logEx.getMessage();
      }
      LOG.error(
          "Fineract container failed to start. Image: {}. Container logs:\n{}",
          fineract.getDockerImageName(),
          containerLogs);
      throw new ExceptionInInitializerError(e);
    }
  }

  protected static int getFineractPort() {
    return fineract.getMappedPort(8443);
  }

  /**
   * Executes a SQL statement inside the Postgres test container using {@code psql}. Uses {@code
   * ON_ERROR_STOP=1} so that any SQL error causes the execution to fail immediately, preventing
   * partial state corruption.
   */
  protected static void executeSqlInPostgres(String sql) {
    Container.ExecResult result =
        execPsql(
                """
                BEGIN;
                %s
                COMMIT;
                """
                .formatted(sql),
            false);
    if (result.getExitCode() != 0) {
      throw new RuntimeException("Failed to execute SQL in test database: " + result.getStderr());
    }
  }

  /**
   * Executes a SQL statement inside the Postgres test container after safely rendering parameters
   * as SQL literals for test-only usage.
   */
  protected static void executeSqlInPostgres(String sqlTemplate, Object... parameters) {
    String[] rendered = new String[parameters.length];
    for (int index = 0; index < parameters.length; index++) {
      rendered[index] = sqlLiteral(parameters[index]);
    }
    executeSqlInPostgres(sqlTemplate.formatted((Object[]) rendered));
  }

  /** Queries a single scalar value from the Postgres test container using {@code psql -tA}. */
  protected static String querySingleValueInPostgres(String sql) {
    Container.ExecResult result = execPsql(sql, true);
    if (result.getExitCode() != 0) {
      throw new RuntimeException("Failed to query test database: " + result.getStderr());
    }
    return result
        .getStdout()
        .lines()
        .map(String::trim)
        .filter(line -> !line.isEmpty())
        .findFirst()
        .orElse("");
  }

  /**
   * Wraps a Java string value as a SQL literal, escaping single quotes. Returns the string {@code
   * NULL} for null input.
   */
  protected static String sqlLiteral(String value) {
    if (value == null) {
      return "NULL";
    }
    return "'" + value.replace("'", "''") + "'";
  }

  /** Wraps a Java value as a SQL literal for test statements executed via {@code psql}. */
  protected static String sqlLiteral(Object value) {
    return sqlLiteral(value == null ? null : String.valueOf(value));
  }

  private static Container.ExecResult execPsql(String sql, boolean tuplesOnly) {
    List<String> command = new ArrayList<>();
    command.add("psql");
    command.add("-v");
    command.add("ON_ERROR_STOP=1");
    command.add("-U");
    command.add(postgres.getUsername());
    command.add("-d");
    command.add(postgres.getDatabaseName());
    if (tuplesOnly) {
      command.add("-t");
      command.add("-A");
    }
    command.add("-c");
    command.add(sql);
    try {
      return postgres.execInContainer(command.toArray(String[]::new));
    } catch (Exception e) {
      throw new RuntimeException("Failed to execute SQL in Postgres test container", e);
    }
  }
}
