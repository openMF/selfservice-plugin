/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.runreport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.apache.fineract.infrastructure.dataqueries.api.RunreportsApiResource;
import org.apache.fineract.infrastructure.security.exception.NoAuthorizationException;
import org.apache.fineract.portfolio.client.domain.Client;
import org.apache.fineract.selfservice.security.service.PlatformSelfServiceSecurityContext;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUser;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUserClientMapping;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.jdbc.core.JdbcTemplate;

class SelfRunReportApiResourceTest {

  @Test
  void runReport_deniesWhenNotAllowlisted() {
    PlatformSelfServiceSecurityContext ctx = mock(PlatformSelfServiceSecurityContext.class);
    RunreportsApiResource core = mock(RunreportsApiResource.class);
    SelfRunReportApiResource resource = new SelfRunReportApiResource(ctx, core, mock(JdbcTemplate.class));
    ReflectionTestUtils.setField(resource, "allowlistedReportsCsv", "");

    when(ctx.authenticatedSelfServiceUser()).thenReturn(mock(AppSelfServiceUser.class));

    assertThrows(
        NoAuthorizationException.class,
        () -> resource.runReport("Client Details", mock(UriInfo.class)));
  }

  @Test
  void runReport_preservesReportParamsAndInjectsMappedClientId() {
    PlatformSelfServiceSecurityContext ctx = mock(PlatformSelfServiceSecurityContext.class);
    RunreportsApiResource core = mock(RunreportsApiResource.class);
    SelfRunReportApiResource resource = new SelfRunReportApiResource(ctx, core, mock(JdbcTemplate.class));
    ReflectionTestUtils.setField(resource, "allowlistedReportsCsv", "Client Details");

    // The authenticated user is mapped to client 123.
    Client client = mock(Client.class);
    when(client.getId()).thenReturn(123L);

    AppSelfServiceUserClientMapping mapping = mock(AppSelfServiceUserClientMapping.class);
    when(mapping.getClient()).thenReturn(client);

    AppSelfServiceUser user = mock(AppSelfServiceUser.class);
    when(user.getAppUserClientMappings()).thenReturn(java.util.Set.of(mapping));
    when(ctx.authenticatedSelfServiceUser()).thenReturn(user);

    // Simulate caller-provided parameters, including a forged client ID.
    MultivaluedMap<String, String> qp = new MultivaluedHashMap<>();
    qp.putSingle("R_clientId", "999");
    qp.putSingle("R_targetId", "888");
    qp.putSingle("R_startDate", "2026-01-01");
    qp.putSingle("R_endDate", "2026-01-31");
    qp.putSingle("exportCSV", "true");

    UriInfo uriInfo = mock(UriInfo.class);
    when(uriInfo.getQueryParameters(true)).thenReturn(qp);

    Response expected = mock(Response.class);
    when(core.runReport(eq("Client Details"), any(UriInfo.class))).thenReturn(expected);

    Response response = resource.runReport("Client Details", uriInfo);
    assertSame(expected, response);

    ArgumentCaptor<UriInfo> captor = ArgumentCaptor.forClass(UriInfo.class);
    verify(core).runReport(eq("Client Details"), captor.capture());

    MultivaluedMap<String, String> forwarded = captor.getValue().getQueryParameters(true);

    // The caller cannot override the authenticated user's client ID.
    assertEquals("123", forwarded.getFirst("R_clientId"));

    // Other report parameters and export options are preserved.
    assertEquals("888", forwarded.getFirst("R_targetId"));
    assertEquals("2026-01-01", forwarded.getFirst("R_startDate"));
    assertEquals("2026-01-31", forwarded.getFirst("R_endDate"));
    assertEquals("true", forwarded.getFirst("exportCSV"));
  }
  @Test
void runReport_preservesPdfExportParameters() {
  PlatformSelfServiceSecurityContext ctx = mock(PlatformSelfServiceSecurityContext.class);
  RunreportsApiResource core = mock(RunreportsApiResource.class);
  SelfRunReportApiResource resource = new SelfRunReportApiResource(ctx, core, mock(JdbcTemplate.class));
  ReflectionTestUtils.setField(resource, "allowlistedReportsCsv", "Client Details");

  Client client = mock(Client.class);
  when(client.getId()).thenReturn(123L);

  AppSelfServiceUserClientMapping mapping = mock(AppSelfServiceUserClientMapping.class);
  when(mapping.getClient()).thenReturn(client);

  AppSelfServiceUser user = mock(AppSelfServiceUser.class);
  when(user.getAppUserClientMappings()).thenReturn(java.util.Set.of(mapping));
  when(ctx.authenticatedSelfServiceUser()).thenReturn(user);

  MultivaluedMap<String, String> qp = new MultivaluedHashMap<>();
  qp.putSingle("output-type", "PDF");
  qp.putSingle("R_startDate", "2026-01-01");

  UriInfo uriInfo = mock(UriInfo.class);
  when(uriInfo.getQueryParameters(true)).thenReturn(qp);

  Response expected = mock(Response.class);
  when(core.runReport(eq("Client Details"), any(UriInfo.class))).thenReturn(expected);

  assertSame(expected, resource.runReport("Client Details", uriInfo));

  ArgumentCaptor<UriInfo> captor = ArgumentCaptor.forClass(UriInfo.class);
  verify(core).runReport(eq("Client Details"), captor.capture());

  MultivaluedMap<String, String> forwarded = captor.getValue().getQueryParameters(true);

  assertEquals("PDF", forwarded.getFirst("output-type"));
  assertEquals("2026-01-01", forwarded.getFirst("R_startDate"));
  assertEquals("123", forwarded.getFirst("R_clientId"));
}
}