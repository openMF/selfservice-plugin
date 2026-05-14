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
package org.apache.fineract.selfservice.runreport;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.PathSegment;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.core.UriInfo;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.dataqueries.api.RunreportsApiResource;
import org.apache.fineract.infrastructure.security.exception.NoAuthorizationException;
import org.apache.fineract.selfservice.security.service.PlatformSelfServiceSecurityContext;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Path("/v1/self/runreports")
@Component("selfservicePluginSelfRunReportApiResource")
@Tag(
    name = "Self Run Report",
    description =
        "This resource allows you to run and receive output from pre-defined Apache Fineract reports.\n"
            + "\n"
            + "The default output is a JSON formatted \"Generic Resultset\". The Generic Resultset contains Column Heading as well as Data information. However, you can export to CSV format by simply adding \"&exportCSV=true\" to the end of your URL.\n"
            + "\n"
            + "If Pentaho reports have been pre-defined, they can also be run through this resource. Pentaho reports can return HTML, PDF or CSV formats.\n"
            + "\n"
            + "The Apache Fineract reference application uses a JQuery plugin called stretchyreporting which, itself, uses this reports resource to provide a pretty flexible reporting User Interface (UI).\n"
            + "\n"
            + "ARGUMENTS\n"
            + "R_'parameter names' ... optional, No defaults The number and names of the parameters depend on the specific report and how it has been configured. R_officeId is an example parameter name.Note: the prefix R_ stands for ReportinggenericResultSetoptional, defaults to true If 'true' an optimised JSON format is returned suitable for tabular display of data. If 'false' a simple JSON format is returned. parameterType optional, The only valid value is 'true'. If any other value is provided the argument will be ignored Determines whether the request looks in the list of reports or the list of parameters for its data. Doesn't apply to Pentaho reports.exportCSV optional, The only valid value is 'true'. If any other value is provided the argument will be ignored Output will be delivered as a CSV file instead of JSON. Doesn't apply to Pentaho reports.output-type optional, Defaults to HTML. Valid Values are HTML, XLS, XSLX, CSV and PDF for html, Excel, Excel 2007+, CSV and PDF formats respectively.Only applies to Pentaho reports.locale optional Any valid locale Ex: en_US, en_IN, fr_FR etcOnly applies to Pentaho reports.")
@RequiredArgsConstructor
public class SelfRunReportApiResource {

  private final PlatformSelfServiceSecurityContext context;
  private final RunreportsApiResource runreportsApiResource;

  /**
   * Comma-separated allowlist of report names permitted for self-service. Deny by default when
   * empty to avoid IDOR via tenant-custom reports.
   */
  @Value("${fineract.modules.selfservice.runreports.allowlist:}")
  private String allowlistedReportsCsv;

  @GET
  @Path("{reportName}")
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({
    MediaType.APPLICATION_JSON,
    "text/csv",
    "application/vnd.ms-excel",
    "application/pdf",
    "text/html"
  })
  @Operation(
      summary = "Running A Report",
      description =
          ""
              + "Example Requests:\n"
              + "\n"
              + "\n"
              + "self/runreports/Client%20Details?R_officeId=1"
              + "\n"
              + "\n"
              + "\n"
              + "self/runreports/Client%20Details?R_officeId=1&exportCSV=true")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "OK",
        content =
            @Content(
                schema =
                    @Schema(
                        implementation =
                            SelfRunReportApiResourceSwagger.GetRunReportResponse.class)))
  })
  public Response runReport(
      @PathParam("reportName") @Parameter(description = "reportName") final String reportName,
      @Context final UriInfo uriInfo) {

    final AppSelfServiceUser user = this.context.authenticatedSelfServiceUser();

    final Set<String> allowlist =
        Arrays.stream((allowlistedReportsCsv == null ? "" : allowlistedReportsCsv).split(","))
            .map(String::trim)
            .filter(s -> !s.isBlank())
            .map(s -> s.toLowerCase(Locale.ROOT))
            .collect(Collectors.toSet());

    if (allowlist.isEmpty() || !allowlist.contains(reportName.toLowerCase(Locale.ROOT))) {
      throw new NoAuthorizationException(
          "Self-service is not permitted to run this report: " + reportName);
    }

    // Scrub all R_* parameters and re-inject trusted scoping params derived from the
    // authenticated self-service user mapping.
    final MultivaluedMap<String, String> qp = new MultivaluedHashMap<>();
    uriInfo
        .getQueryParameters(true)
        .forEach(
            (k, v) -> {
              if (!k.startsWith("R_")) {
                qp.put(k, v);
              }
            });

    // Force report scope to the user's mapped clientId (if any).
    final Long mappedClientId =
        user.getAppUserClientMappings() == null || user.getAppUserClientMappings().isEmpty()
            ? null
            : user.getAppUserClientMappings().iterator().next().getClient().getId();
    if (mappedClientId != null) {
      qp.putSingle("R_clientId", String.valueOf(mappedClientId));
    }

    return this.runreportsApiResource.runReport(
        reportName, new UriInfoWithQueryParams(uriInfo, qp));
  }

  /** Minimal UriInfo wrapper overriding query parameters only. */
  static final class UriInfoWithQueryParams implements UriInfo {
    private final UriInfo delegate;
    private final MultivaluedMap<String, String> queryParams;

    UriInfoWithQueryParams(UriInfo delegate, MultivaluedMap<String, String> queryParams) {
      this.delegate = delegate;
      this.queryParams = queryParams;
    }

    @Override
    public MultivaluedMap<String, String> getQueryParameters() {
      return queryParams;
    }

    @Override
    public MultivaluedMap<String, String> getQueryParameters(boolean decode) {
      return queryParams;
    }

    @Override
    public URI getRequestUri() {
      return delegate.getRequestUri();
    }

    @Override
    public UriBuilder getRequestUriBuilder() {
      return delegate.getRequestUriBuilder();
    }

    @Override
    public URI getAbsolutePath() {
      return delegate.getAbsolutePath();
    }

    @Override
    public UriBuilder getAbsolutePathBuilder() {
      return delegate.getAbsolutePathBuilder();
    }

    @Override
    public URI getBaseUri() {
      return delegate.getBaseUri();
    }

    @Override
    public UriBuilder getBaseUriBuilder() {
      return delegate.getBaseUriBuilder();
    }

    @Override
    public String getPath() {
      return delegate.getPath();
    }

    @Override
    public String getPath(boolean decode) {
      return delegate.getPath(decode);
    }

    @Override
    public List<PathSegment> getPathSegments() {
      return delegate.getPathSegments();
    }

    @Override
    public List<PathSegment> getPathSegments(boolean decode) {
      return delegate.getPathSegments(decode);
    }

    @Override
    public MultivaluedMap<String, String> getPathParameters() {
      return delegate.getPathParameters();
    }

    @Override
    public MultivaluedMap<String, String> getPathParameters(boolean decode) {
      return delegate.getPathParameters(decode);
    }

    @Override
    public List<String> getMatchedURIs() {
      return delegate.getMatchedURIs();
    }

    @Override
    public List<String> getMatchedURIs(boolean decode) {
      return delegate.getMatchedURIs(decode);
    }

    @Override
    public List<Object> getMatchedResources() {
      return delegate.getMatchedResources();
    }

    @Override
    public URI resolve(URI uri) {
      return delegate.resolve(uri);
    }

    @Override
    public URI relativize(URI uri) {
      return delegate.relativize(uri);
    }
  }
}
