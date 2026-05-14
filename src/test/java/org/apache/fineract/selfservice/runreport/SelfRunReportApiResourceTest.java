/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.runreport;

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

class SelfRunReportApiResourceTest {

  @Test
  void runReport_deniesWhenNotAllowlisted() {
    PlatformSelfServiceSecurityContext ctx = mock(PlatformSelfServiceSecurityContext.class);
    RunreportsApiResource core = mock(RunreportsApiResource.class);
    SelfRunReportApiResource resource = new SelfRunReportApiResource(ctx, core);
    ReflectionTestUtils.setField(resource, "allowlistedReportsCsv", "");

    when(ctx.authenticatedSelfServiceUser()).thenReturn(mock(AppSelfServiceUser.class));

    assertThrows(
        NoAuthorizationException.class,
        () -> resource.runReport("Client Details", mock(UriInfo.class)));
  }

  @Test
  void runReport_stripsAllRParamsAndInjectsMappedClientId() {
    PlatformSelfServiceSecurityContext ctx = mock(PlatformSelfServiceSecurityContext.class);
    RunreportsApiResource core = mock(RunreportsApiResource.class);
    SelfRunReportApiResource resource = new SelfRunReportApiResource(ctx, core);
    ReflectionTestUtils.setField(resource, "allowlistedReportsCsv", "Client Details");

    // user has mapped client 123
    Client client = mock(Client.class);
    when(client.getId()).thenReturn(123L);
    AppSelfServiceUserClientMapping mapping = mock(AppSelfServiceUserClientMapping.class);
    when(mapping.getClient()).thenReturn(client);
    AppSelfServiceUser user = mock(AppSelfServiceUser.class);
    when(user.getAppUserClientMappings()).thenReturn(java.util.Set.of(mapping));
    when(ctx.authenticatedSelfServiceUser()).thenReturn(user);

    MultivaluedMap<String, String> qp = new MultivaluedHashMap<>();
    qp.putSingle("R_clientId", "999"); // attacker supplied
    qp.putSingle("R_targetId", "888"); // non-standard param
    qp.putSingle("exportCSV", "true");
    UriInfo uriInfo = mock(UriInfo.class);
    when(uriInfo.getQueryParameters(true)).thenReturn(qp);

    Response expected = mock(Response.class);
    when(core.runReport(eq("Client Details"), any(UriInfo.class))).thenReturn(expected);

    Response resp = resource.runReport("Client Details", uriInfo);
    org.junit.jupiter.api.Assertions.assertSame(expected, resp);

    ArgumentCaptor<UriInfo> captor = ArgumentCaptor.forClass(UriInfo.class);
    verify(core).runReport(eq("Client Details"), captor.capture());

    MultivaluedMap<String, String> forwarded = captor.getValue().getQueryParameters(true);
    // R_* stripped except injected R_clientId
    org.junit.jupiter.api.Assertions.assertEquals("123", forwarded.getFirst("R_clientId"));
    org.junit.jupiter.api.Assertions.assertEquals(null, forwarded.getFirst("R_targetId"));
    // non-R params preserved
    org.junit.jupiter.api.Assertions.assertEquals("true", forwarded.getFirst("exportCSV"));
  }
}
