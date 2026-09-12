/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.tenant.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Request and response shapes for the OpenAPI description of {@link TenantManagementApiResource}.
 *
 * <p>Documentation only, following Fineract's {@code *ApiResourceSwagger} convention. The resource
 * parses JSON through {@code FromJsonHelper} and serialises with Gson, so these classes describe
 * the wire format without taking part in it.
 */
final class TenantManagementApiResourceSwagger {

  private TenantManagementApiResourceSwagger() {}

  @Schema(description = "PostTenantsRequest")
  static final class PostTenantsRequest {
    private PostTenantsRequest() {}

    @Schema(
        requiredMode = Schema.RequiredMode.REQUIRED,
        example = "acme",
        description = "Unique, lower case; cannot be changed later")
    public String identifier;

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "Acme Microfinance")
    public String name;

    @Schema(
        requiredMode = Schema.RequiredMode.REQUIRED,
        example = "Asia/Kolkata",
        description = "IANA time zone")
    public String timezoneId;

    @Schema(example = "ACTIVE", description = "ACTIVE, INACTIVE or SUSPENDED; defaults to ACTIVE")
    public String status;

    @Schema(example = "Pilot tenant")
    public String description;

    @Schema(example = "ops@acme.example.org")
    public String contactEmail;

    @Schema(
        requiredMode = Schema.RequiredMode.REQUIRED,
        example = "mifostenant_acme",
        description =
            "Letters, digits and underscore, starting with a letter or underscore, at most 63"
                + " characters, stored in lower case. Created if absent; must not belong to another"
                + " tenant.")
    public String schemaName;

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "localhost")
    public String schemaServer;

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "5432", description = "1-65535")
    public String schemaServerPort;

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "fineract")
    public String schemaUsername;

    @Schema(
        requiredMode = Schema.RequiredMode.REQUIRED,
        example = "a-unique-database-secret",
        description = "Write-only: stored encrypted and never returned by any endpoint")
    public String schemaPassword;

    @Schema(example = "sslmode=require")
    public String schemaConnectionParameters;

    @Schema(example = "true", description = "Migrate the schema on startup; defaults to true")
    public Boolean autoUpdate;
  }

  @Schema(
      description =
          "PutTenantsRequest - every field optional. Omitted fields are unchanged; blank values are"
              + " refused except for description, contactEmail and schemaConnectionParameters,"
              + " which an empty string or null clears.")
  static final class PutTenantsRequest {
    private PutTenantsRequest() {}

    @Schema(example = "Acme Microfinance Ltd")
    public String name;

    @Schema(example = "Asia/Kolkata")
    public String timezoneId;

    @Schema(example = "")
    public String description;

    @Schema(example = "newops@acme.example.org")
    public String contactEmail;

    @Schema(example = "db.internal")
    public String schemaServer;

    @Schema(example = "5432")
    public String schemaServerPort;

    @Schema(example = "fineract")
    public String schemaUsername;

    @Schema(description = "Write-only; omit to keep the stored password")
    public String schemaPassword;

    @Schema(example = "sslmode=require")
    public String schemaConnectionParameters;

    @Schema(example = "true")
    public Boolean autoUpdate;
  }

  @Schema(description = "PostTenantsTestConnectionRequest")
  static final class PostTestConnectionRequest {
    private PostTestConnectionRequest() {}

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "mifostenant_acme")
    public String schemaName;

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "localhost")
    public String schemaServer;

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "5432")
    public String schemaServerPort;

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "fineract")
    public String schemaUsername;

    @Schema(
        requiredMode = Schema.RequiredMode.REQUIRED,
        description = "Used for this probe only; never stored")
    public String schemaPassword;

    @Schema(example = "sslmode=require")
    public String schemaConnectionParameters;
  }

  @Schema(description = "GetTenantConnectionResponse - never includes a password")
  static final class GetTenantConnectionResponse {
    private GetTenantConnectionResponse() {}

    @Schema(example = "3")
    public Long id;

    @Schema(example = "mifostenant_acme")
    public String schemaName;

    @Schema(example = "localhost")
    public String schemaServer;

    @Schema(example = "5432")
    public String schemaServerPort;

    @Schema(example = "fineract")
    public String schemaUsername;

    @Schema(example = "sslmode=require")
    public String schemaConnectionParameters;

    @Schema(example = "true")
    public Boolean autoUpdate;
  }

  @Schema(description = "GetTenantResponse")
  static final class GetTenantResponse {
    private GetTenantResponse() {}

    @Schema(example = "3")
    public Long id;

    @Schema(example = "acme")
    public String identifier;

    @Schema(example = "Acme Microfinance")
    public String name;

    @Schema(example = "Asia/Kolkata")
    public String timezoneId;

    @Schema(
        example = "ACTIVE",
        nullable = true,
        allowableValues = {"ACTIVE", "INACTIVE", "SUSPENDED"},
        description =
            "null when the stored status is not one this plugin recognises; such a tenant is"
                + " refused until its status is set again")
    public String status;

    @Schema(example = "Pilot tenant")
    public String description;

    @Schema(example = "ops@acme.example.org")
    public String contactEmail;

    @Schema(example = "[2026, 9, 14]", description = "Year, month, day")
    public List<Integer> joinedDate;

    @Schema(example = "2026-09-14T10:15:30Z")
    public String createdDate;

    @Schema(example = "2026-09-14T11:02:00Z")
    public String lastModifiedDate;

    public GetTenantConnectionResponse connection;
  }

  @Schema(description = "GetTenantsResponse")
  static final class GetTenantsResponse {
    private GetTenantsResponse() {}

    @Schema(example = "1")
    public Integer totalFilteredRecords;

    public List<GetTenantResponse> pageItems;
  }

  @Schema(description = "GetTenantsTemplateResponse")
  static final class GetTenantsTemplateResponse {
    private GetTenantsTemplateResponse() {}

    @Schema(example = "[\"Africa/Abidjan\", \"Asia/Kolkata\"]")
    public List<String> timezones;

    @Schema(example = "[\"ACTIVE\", \"INACTIVE\", \"SUSPENDED\"]")
    public List<String> statuses;
  }

  @Schema(description = "DeleteTenantsResponse")
  static final class DeleteTenantResponse {
    private DeleteTenantResponse() {}

    @Schema(example = "3")
    public Long resourceId;
  }

  @Schema(description = "PostTenantsTestConnectionResponse")
  static final class PostTestConnectionResponse {
    private PostTestConnectionResponse() {}

    @Schema(example = "true")
    public Boolean reachable;
  }
}
