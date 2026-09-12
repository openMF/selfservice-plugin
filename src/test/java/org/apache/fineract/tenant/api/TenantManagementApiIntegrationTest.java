/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.tenant.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;

import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.fineract.selfservice.testing.support.SelfServiceIntegrationTestBase;
import org.apache.fineract.selfservice.testing.support.SelfServiceTestUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@code /v1/admin/tenants} over HTTP against a real Fineract with this plugin loaded.
 *
 * <p>Tenant management runs in the master context: requests authenticate as a master user (created
 * at container start by {@code TenantMasterUserBootstrap} from the test base's environment) and
 * carry no tenant header. Tenant users - including a tenant's own {@code mifos} super user - are
 * refused.
 *
 * <p>Creating a tenant runs a complete schema migration, so the lifecycle is one test rather than a
 * chain of order-dependent ones. The shared {@code default} tenant is never mutated: other
 * integration test classes run against it in the same containers.
 */
class TenantManagementApiIntegrationTest extends SelfServiceIntegrationTestBase {

  private static final String TENANTS_PATH =
      SelfServiceTestUtils.CONTEXT_PATH + "/api/v1/admin/tenants";
  private static final String OFFICES_PATH = SelfServiceTestUtils.CONTEXT_PATH + "/api/v1/offices";

  private static final String MASTER_USERNAME = "master";
  private static final String MASTER_PASSWORD = "master-password-for-tests";

  private static RequestSpecification base() {
    return given()
        .relaxedHTTPSValidation()
        .baseUri("https://localhost")
        .port(getFineractPort())
        .contentType(ContentType.JSON)
        .accept(ContentType.JSON);
  }

  /** A master user's request: no tenant header, because the master context has none. */
  private static RequestSpecification asMaster() {
    return base()
        .header(
            "Authorization",
            SelfServiceTestUtils.basicAuthHeader(MASTER_USERNAME, MASTER_PASSWORD));
  }

  /**
   * A tenant user's request.
   *
   * <p>Built directly rather than from {@link SelfServiceTestUtils#requestSpec}, which already
   * carries the {@code default} tenant header; adding a second would send both.
   */
  private static RequestSpecification asTenantUser(
      final String tenant, final String username, final String password) {
    return base()
        .header("Fineract-Platform-TenantId", tenant)
        .header("Authorization", SelfServiceTestUtils.basicAuthHeader(username, password));
  }

  private static Map<String, Object> createBody(final String identifier, final String schemaName) {
    final Map<String, Object> body = new HashMap<>();
    body.put("identifier", identifier);
    body.put("name", "Integration " + identifier);
    body.put("timezoneId", "Asia/Kolkata");
    body.put("schemaName", schemaName);
    // Fineract reaches Postgres on the shared test network under this alias.
    body.put("schemaServer", "db");
    body.put("schemaServerPort", "5432");
    body.put("schemaUsername", "postgres");
    body.put("schemaPassword", "postgres");
    return body;
  }

  // ---------------------------------------------------------------
  // The master context
  // ---------------------------------------------------------------

  @Test
  @DisplayName("GET /v1/admin/tenants without credentials is rejected with 401")
  void listWithoutCredentials_isRejected() {
    base().when().get(TENANTS_PATH).then().statusCode(401);
  }

  @Test
  @DisplayName("A tenant's own super user is not a master user and is rejected with 401")
  void listAsTenantSuperUser_isRejected() {
    // mifos holds ALL_FUNCTIONS inside the default tenant. That is a tenant's permission, not
    // the super master role, so the master context does not recognise the user at all.
    asTenantUser(SelfServiceTestUtils.DEFAULT_TENANT, "mifos", "password")
        .when()
        .get(TENANTS_PATH)
        .then()
        .statusCode(401);
  }

  @Test
  @DisplayName("A master user with a wrong password is rejected with 401")
  void listWithAWrongMasterPassword_isRejected() {
    base()
        .header("Authorization", SelfServiceTestUtils.basicAuthHeader(MASTER_USERNAME, "wrong"))
        .when()
        .get(TENANTS_PATH)
        .then()
        .statusCode(401);
  }

  @Test
  @DisplayName("GET /v1/admin/tenants as a master user lists tenants without credentials")
  void listAsMaster_returnsTenantsWithoutCredentials() {
    final Response response =
        asMaster().when().get(TENANTS_PATH).then().statusCode(200).extract().response();

    assertThat(response.jsonPath().getList("pageItems.identifier", String.class))
        .contains("default");
    assertThat(response.asString()).doesNotContainIgnoringCase("password");
  }

  @Test
  @DisplayName("GET /v1/admin/tenants/template lists the lifecycle statuses")
  void template_listsStatuses() {
    asMaster()
        .when()
        .get(TENANTS_PATH + "/template")
        .then()
        .statusCode(200)
        .body("statuses", equalTo(List.of("ACTIVE", "INACTIVE", "SUSPENDED")));
  }

  @Test
  @DisplayName("Core's /v1/tenants/{tenantId}/oidc-config stays on Fineract's own security chain")
  void coreOidcConfigEndpoint_isNotCapturedByTenantAdministration() {
    // Core Fineract serves this path. While tenant administration claimed /v1/tenants/**,
    // the master chain answered this tenant user's request with 401 before core saw it.
    // Reaching core's resource is proved by its own 404 for a tenant without OIDC setup.
    asTenantUser(SelfServiceTestUtils.DEFAULT_TENANT, "mifos", "password")
        .when()
        .get(SelfServiceTestUtils.CONTEXT_PATH + "/api/v1/tenants/default/oidc-config")
        .then()
        .statusCode(404)
        .body("errors[0].developerMessage", containsString("No OIDC configuration found"));
  }

  // ---------------------------------------------------------------
  // Validation and error mapping
  // ---------------------------------------------------------------

  @Test
  @DisplayName("POST /v1/admin/tenants rejects a schema name PostgreSQL cannot create")
  void create_withANumericLeadingSchemaName_returns400() {
    asMaster()
        .body(createBody("numericschema", "123tenant"))
        .when()
        .post(TENANTS_PATH)
        .then()
        .statusCode(400)
        .body("errors.parameterName", hasItem("schemaName"));
  }

  @Test
  @DisplayName("POST /v1/admin/tenants rejects DDL injection in the schema name")
  void create_withAnInjectionAttempt_returns400() {
    asMaster()
        .body(createBody("injection", "x; DROP DATABASE fineract_default; --"))
        .when()
        .post(TENANTS_PATH)
        .then()
        .statusCode(400)
        .body("errors.parameterName", hasItem("schemaName"));

    asMaster()
        .when()
        .get(TENANTS_PATH + "?search=injection")
        .then()
        .statusCode(200)
        .body("totalFilteredRecords", equalTo(0));
  }

  @Test
  @DisplayName("POST /v1/admin/tenants rejects a port outside 1-65535")
  void create_withAPortOutOfRange_returns400() {
    final Map<String, Object> body = createBody("badport", "mifostenant_badport");
    body.put("schemaServerPort", "70000");

    asMaster()
        .body(body)
        .when()
        .post(TENANTS_PATH)
        .then()
        .statusCode(400)
        .body("errors.parameterName", hasItem("schemaServerPort"));
  }

  @Test
  @DisplayName("GET /v1/admin/tenants/{id} for an unknown tenant returns 404")
  void retrieveUnknownTenant_returns404() {
    asMaster().when().get(TENANTS_PATH + "/999999").then().statusCode(404);
  }

  @Test
  @DisplayName("POST /v1/admin/tenants/{id} with an unknown command returns 400")
  void changeStatus_withAnUnknownCommand_returns400() {
    asMaster()
        .body("{}")
        .when()
        .post(TENANTS_PATH + "/1?command=obliterate")
        .then()
        .statusCode(400);
  }

  @Test
  @DisplayName("GET /v1/admin/tenants with an unknown status filter returns 400")
  void list_withAnUnknownStatusFilter_returns400() {
    asMaster().when().get(TENANTS_PATH + "?status=DELETED").then().statusCode(400);
  }

  @Test
  @DisplayName("POST /v1/admin/tenants/test-connection with a wrong password reports unreachable")
  void testConnection_withAWrongPassword_reportsUnreachable() {
    final Map<String, Object> body = new HashMap<>();
    body.put("schemaName", "fineract_default");
    body.put("schemaServer", "db");
    body.put("schemaServerPort", "5432");
    body.put("schemaUsername", "postgres");
    body.put("schemaPassword", "definitely-wrong");

    final Response response =
        asMaster()
            .body(body)
            .when()
            .post(TENANTS_PATH + "/test-connection")
            .then()
            .statusCode(200)
            .body("reachable", equalTo(false))
            .extract()
            .response();

    assertThat(response.asString()).doesNotContainIgnoringCase("authentication failed");
  }

  // ---------------------------------------------------------------
  // Lifecycle
  // ---------------------------------------------------------------

  @Test
  @DisplayName("A tenant can be created, used, suspended, reactivated and removed over HTTP")
  void fullLifecycle_createUseSuspendReactivateAndRemove() {
    final String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    final String identifier = "it" + suffix;
    final String schemaName = "mifostenant_it" + suffix;

    final Response created =
        asMaster()
            .body(createBody(identifier, schemaName))
            .when()
            .post(TENANTS_PATH)
            .then()
            .statusCode(200)
            .body("identifier", equalTo(identifier))
            .body("status", equalTo("ACTIVE"))
            .extract()
            .response();
    assertThat(created.asString()).doesNotContainIgnoringCase("password");
    final int id = created.jsonPath().getInt("id");

    asMaster().when().get(TENANTS_PATH + "/" + id).then().statusCode(200);

    // Migrated and usable at once, by the administrator seeded into the new tenant...
    asTenantUser(identifier, "mifos", "password").when().get(OFFICES_PATH).then().statusCode(200);

    // ...who is a tenant user, and so cannot administer tenants.
    asTenantUser(identifier, "mifos", "password").when().get(TENANTS_PATH).then().statusCode(401);

    asMaster()
        .body("{}")
        .when()
        .post(TENANTS_PATH + "/" + id + "?command=suspend")
        .then()
        .statusCode(200)
        .body("status", equalTo("SUSPENDED"));

    asTenantUser(identifier, "mifos", "password")
        .when()
        .get(OFFICES_PATH)
        .then()
        .statusCode(503)
        .body("tenantStatus", equalTo("SUSPENDED"));

    // A browser client sends its tenant header on every call. Addressing the master
    // endpoints with the suspended tenant's header must not lock administration out.
    asMaster()
        .header("Fineract-Platform-TenantId", identifier)
        .when()
        .get(TENANTS_PATH + "/" + id)
        .then()
        .statusCode(200);

    asMaster()
        .body("{}")
        .when()
        .post(TENANTS_PATH + "/" + id + "?command=activate")
        .then()
        .statusCode(200);
    asTenantUser(identifier, "mifos", "password").when().get(OFFICES_PATH).then().statusCode(200);

    asMaster()
        .body(Map.of("identifier", "renamed"))
        .when()
        .put(TENANTS_PATH + "/" + id)
        .then()
        .statusCode(400);

    // An active tenant cannot be removed in one step.
    asMaster().when().delete(TENANTS_PATH + "/" + id).then().statusCode(403);

    asMaster()
        .body("{}")
        .when()
        .post(TENANTS_PATH + "/" + id + "?command=deactivate")
        .then()
        .statusCode(200);
    asMaster().when().delete(TENANTS_PATH + "/" + id).then().statusCode(200);
    asMaster().when().get(TENANTS_PATH + "/" + id).then().statusCode(404);
  }
}
