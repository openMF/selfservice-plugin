/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.tenant.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Keeps {@code api-reference/openapi/tenant-management.yaml} in step with the resource annotations.
 *
 * <p>Regenerate after changing the API with {@code ./mvnw test
 * -Dtest=TenantManagementOpenApiSpecTest -Dopenapi.update=true}.
 */
class TenantManagementOpenApiSpecTest {

  private static final Path COMMITTED_SPEC =
      Path.of("api-reference/openapi/tenant-management.yaml");

  @Test
  void theCommittedSpecMatchesTheAnnotations() throws Exception {
    final String generated = TenantManagementOpenApiSpec.generate();

    if (Boolean.getBoolean("openapi.update")) {
      Files.createDirectories(COMMITTED_SPEC.getParent());
      Files.writeString(COMMITTED_SPEC, generated);
    }

    assertTrue(
        Files.exists(COMMITTED_SPEC),
        COMMITTED_SPEC
            + " is missing. Generate it with: ./mvnw test"
            + " -Dtest=TenantManagementOpenApiSpecTest -Dopenapi.update=true");
    assertEquals(
        generated,
        Files.readString(COMMITTED_SPEC).replace("\r\n", "\n"),
        COMMITTED_SPEC
            + " is out of date with TenantManagementApiResource. Regenerate it with: ./mvnw test"
            + " -Dtest=TenantManagementOpenApiSpecTest -Dopenapi.update=true");
  }

  @Test
  void everyEndpointIsDocumentedUnderTheAdminNamespace() throws Exception {
    final String spec = TenantManagementOpenApiSpec.generate();

    for (final String path :
        new String[] {
          "/v1/admin/tenants:",
          "/v1/admin/tenants/template:",
          "/v1/admin/tenants/{id}:",
          "/v1/admin/tenants/test-connection:"
        }) {
      assertTrue(spec.contains(path), "missing path " + path);
    }
    // Core owns /v1/tenants/{tenantId}/oidc-config; nothing here may claim that namespace.
    assertFalse(spec.contains("\n  /v1/tenants"), "tenant administration must not use /v1/tenants");
  }

  @Test
  void noResponseSchemaCarriesAPassword() throws Exception {
    final String spec = TenantManagementOpenApiSpec.generate();

    final int responses = spec.indexOf("GetTenantConnectionResponse:");
    assertTrue(responses >= 0, "connection response schema missing");
    final String connectionSchema =
        spec.substring(
            responses,
            spec.indexOf("\n    Get", responses + 1) > 0
                ? spec.indexOf("\n    Get", responses + 1)
                : spec.length());
    assertFalse(connectionSchema.contains("Password"), "a response schema exposes a password");
  }
}
