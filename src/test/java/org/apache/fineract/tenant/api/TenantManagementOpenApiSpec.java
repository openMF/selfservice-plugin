/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.tenant.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.swagger.v3.core.util.Yaml;
import io.swagger.v3.jaxrs2.Reader;
import io.swagger.v3.oas.integration.SwaggerConfiguration;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.util.Set;

/**
 * Generates the OpenAPI description of {@link TenantManagementApiResource} from its annotations.
 *
 * <p>Output is key-sorted so it is identical on every run, whatever order reflection happens to
 * return the resource's methods in - otherwise the committed file would churn and the drift test
 * would flake.
 */
final class TenantManagementOpenApiSpec {

  static final String SECURITY_SCHEME = "masterBasicAuth";

  private TenantManagementOpenApiSpec() {}

  static String generate() throws Exception {
    final OpenAPI base =
        new OpenAPI()
            .info(
                new Info()
                    .title("Fineract Tenant Management API")
                    .version("1.0")
                    .description(
                        "Tenant lifecycle administration provided by the Mifos self-service plugin"
                            + " (MX-406). Served in a master context: authenticate as a master user"
                            + " holding the SUPER_MASTER role, with no tenant header."))
            .addServersItem(
                new Server()
                    .url("/fineract-provider/api")
                    .description("Apache Fineract with the self-service plugin loaded"))
            .components(
                new Components()
                    .addSecuritySchemes(
                        SECURITY_SCHEME,
                        new SecurityScheme()
                            .type(SecurityScheme.Type.HTTP)
                            .scheme("basic")
                            .description("A master user holding the SUPER_MASTER role")))
            .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME));

    final OpenAPI spec =
        new Reader(new SwaggerConfiguration().openAPI(base))
            .read(Set.of(TenantManagementApiResource.class));

    final ObjectMapper mapper =
        Yaml.mapper().copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    return mapper.writeValueAsString(spec);
  }
}
