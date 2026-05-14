/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.client.api;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.UriInfo;
import java.io.InputStream;
import org.apache.fineract.infrastructure.core.api.ApiRequestParameterHelper;
import org.apache.fineract.infrastructure.core.serialization.ApiRequestJsonSerializationSettings;
import org.apache.fineract.infrastructure.core.serialization.DefaultToApiJsonSerializer;
import org.apache.fineract.infrastructure.core.serialization.ToApiJsonSerializer;
import org.apache.fineract.infrastructure.core.service.Page;
import org.apache.fineract.infrastructure.core.service.SearchParameters;
import org.apache.fineract.infrastructure.documentmanagement.data.ImageCreateRequest;
import org.apache.fineract.infrastructure.documentmanagement.data.ImageCreateResponse;
import org.apache.fineract.infrastructure.documentmanagement.service.ImageReadPlatformService;
import org.apache.fineract.infrastructure.documentmanagement.service.ImageWritePlatformService;
import org.apache.fineract.infrastructure.security.exception.NoAuthorizationException;
import org.apache.fineract.portfolio.accountdetails.data.AccountSummaryCollectionData;
import org.apache.fineract.portfolio.accountdetails.service.AccountDetailsReadPlatformService;
import org.apache.fineract.portfolio.client.api.ClientApiConstants;
import org.apache.fineract.portfolio.client.data.ClientChargeData;
import org.apache.fineract.portfolio.client.data.ClientData;
import org.apache.fineract.portfolio.client.data.ClientTransactionData;
import org.apache.fineract.portfolio.client.service.ClientChargeReadPlatformService;
import org.apache.fineract.portfolio.client.service.ClientTransactionReadPlatformService;
import org.apache.fineract.portfolio.loanaccount.guarantor.data.ObligeeData;
import org.apache.fineract.portfolio.loanaccount.guarantor.service.GuarantorReadPlatformService;
import org.apache.fineract.selfservice.client.data.SelfClientDataValidator;
import org.apache.fineract.selfservice.client.service.AppSelfServiceUserClientMapperReadService;
import org.apache.fineract.selfservice.client.service.SelfServiceClientReadPlatformService;
import org.apache.fineract.selfservice.client.service.SelfServiceSearchParameters;
import org.apache.fineract.selfservice.security.service.PlatformSelfServiceSecurityContext;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUser;
import org.glassfish.jersey.media.multipart.FormDataBodyPart;
import org.glassfish.jersey.media.multipart.FormDataContentDisposition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class SelfClientsApiResourceTest {

  @Mock private PlatformSelfServiceSecurityContext context;
  @Mock private SelfServiceClientReadPlatformService selfServiceClientReadPlatformService;
  @Mock private AccountDetailsReadPlatformService accountDetailsReadPlatformService;
  @Mock private ClientChargeReadPlatformService clientChargeReadPlatformService;
  @Mock private ClientTransactionReadPlatformService clientTransactionReadPlatformService;
  @Mock private GuarantorReadPlatformService guarantorReadPlatformService;
  @Mock private ToApiJsonSerializer<ClientData> clientSerializer;
  @Mock private ToApiJsonSerializer<AccountSummaryCollectionData> accountSummarySerializer;
  @Mock private DefaultToApiJsonSerializer<ClientChargeData> clientChargeSerializer;
  @Mock private DefaultToApiJsonSerializer<ClientTransactionData> clientTransactionSerializer;
  @Mock private DefaultToApiJsonSerializer<ObligeeData> obligeeSerializer;
  @Mock private ApiRequestParameterHelper apiRequestParameterHelper;
  @Mock private AppSelfServiceUserClientMapperReadService appUserClientMapperReadService;
  @Mock private SelfClientDataValidator dataValidator;
  @Mock private ImageReadPlatformService imageReadPlatformService;
  @Mock private ImageWritePlatformService imageWritePlatformService;
  @Mock private UriInfo uriInfo;

  private SelfClientsApiResource resource;

  private static final Long USER_ID = 10L;
  private static final Long CLIENT_ID = 5L;

  @BeforeEach
  void setUp() {
    resource =
        new SelfClientsApiResource(
            context,
            selfServiceClientReadPlatformService,
            accountDetailsReadPlatformService,
            clientChargeReadPlatformService,
            clientTransactionReadPlatformService,
            guarantorReadPlatformService,
            clientSerializer,
            accountSummarySerializer,
            clientChargeSerializer,
            clientTransactionSerializer,
            obligeeSerializer,
            apiRequestParameterHelper,
            appUserClientMapperReadService,
            dataValidator,
            imageReadPlatformService,
            imageWritePlatformService,
            null, // imageResizeContentProcessor
            null, // base64EncoderContentProcessor
            null, // base64DecoderContentProcessor
            null, // dataUrlEncoderContentProcessor
            null, // dataUrlDecoderContentProcessor
            null, // sizeContentProcessor
            null // contentDetectorManager
            );

    org.mockito.Mockito.lenient()
        .when(uriInfo.getQueryParameters())
        .thenReturn(new MultivaluedHashMap<>());
    org.mockito.Mockito.lenient()
        .when(apiRequestParameterHelper.process(any()))
        .thenReturn(mock(ApiRequestJsonSerializationSettings.class));
  }

  private void mockAuthenticatedUser() {
    AppSelfServiceUser user = mock(AppSelfServiceUser.class);
    org.mockito.Mockito.lenient().when(user.getId()).thenReturn(USER_ID);
    org.mockito.Mockito.lenient().when(context.authenticatedSelfServiceUser()).thenReturn(user);
  }

  private void mockClientMapped() {
    mockAuthenticatedUser();
    org.mockito.Mockito.lenient()
        .when(appUserClientMapperReadService.isClientMappedToSelfServiceUser(CLIENT_ID, USER_ID))
        .thenReturn(true);
  }

  private void mockClientNotMapped() {
    mockAuthenticatedUser();
    org.mockito.Mockito.lenient()
        .when(appUserClientMapperReadService.isClientMappedToSelfServiceUser(CLIENT_ID, USER_ID))
        .thenReturn(false);
  }

  // --- retrieveAll ---

  @Test
  void retrieveAll_checksPermission() {
    Page<ClientData> page = mock(Page.class);
    when(selfServiceClientReadPlatformService.retrieveAll(any(SelfServiceSearchParameters.class)))
        .thenReturn(page);
    when(clientSerializer.serialize(any(), any(Page.class))).thenReturn("[]");

    resource.retrieveAll(uriInfo, null, null, null, null, null, null, null, null, null);

    verify(context).validateHasReadPermission(ClientApiConstants.CLIENT_RESOURCE_NAME);
    verify(selfServiceClientReadPlatformService)
        .retrieveAll(any(SelfServiceSearchParameters.class));
  }

  @Test
  void retrieveAll_throwsIfNoPermission() {
    doThrow(new NoAuthorizationException("no auth")).when(context).validateHasReadPermission(any());

    assertThrows(
        NoAuthorizationException.class,
        () -> resource.retrieveAll(uriInfo, null, null, null, null, null, null, null, null, null));
    verify(selfServiceClientReadPlatformService, never()).retrieveAll(any());
  }

  // --- retrieveOne ---

  @Test
  void retrieveOne_checksPermission() {
    mockClientMapped();
    ClientData clientData = ClientData.emptyInstance(CLIENT_ID);
    when(selfServiceClientReadPlatformService.retrieveOne(CLIENT_ID)).thenReturn(clientData);
    when(clientSerializer.serialize(any(), eq(clientData))).thenReturn("{\"id\":5}");

    resource.retrieveOne(CLIENT_ID, uriInfo);

    verify(context).validateHasReadPermission(ClientApiConstants.CLIENT_RESOURCE_NAME);
  }

  @Test
  void retrieveOne_throwsIfNoPermission() {
    mockClientMapped();
    doThrow(new NoAuthorizationException("no auth")).when(context).validateHasReadPermission(any());

    assertThrows(NoAuthorizationException.class, () -> resource.retrieveOne(CLIENT_ID, uriInfo));
    verify(selfServiceClientReadPlatformService, never()).retrieveOne(anyLong());
  }

  // --- retrieveAssociatedAccounts ---

  @Test
  void retrieveAssociatedAccounts_filtersParametersCorrectly() {
    mockClientMapped();
    AccountSummaryCollectionData accounts = mock(AccountSummaryCollectionData.class);
    when(accountDetailsReadPlatformService.retrieveClientAccountDetails(CLIENT_ID))
        .thenReturn(accounts);
    when(accountSummarySerializer.serialize(
            any(ApiRequestJsonSerializationSettings.class),
            eq(accounts),
            eq(ClientApiConstants.CLIENT_ACCOUNTS_DATA_PARAMETERS)))
        .thenReturn("{\"mocked\":true}");

    String result = resource.retrieveAssociatedAccounts(CLIENT_ID, uriInfo);

    verify(context).validateHasReadPermission(ClientApiConstants.CLIENT_RESOURCE_NAME);
    verify(accountDetailsReadPlatformService).retrieveClientAccountDetails(CLIENT_ID);
    assertNotNull(result);
  }

  // --- retrieveAllClientTransactions ---

  @Test
  void retrieveAllClientTransactions_checksPermissionAndDelegates() {
    mockClientMapped();
    Page<ClientTransactionData> page = mock(Page.class);
    when(clientTransactionReadPlatformService.retrieveAllTransactions(
            eq(CLIENT_ID), org.mockito.ArgumentMatchers.isA(SearchParameters.class)))
        .thenReturn(page);
    when(clientTransactionSerializer.serialize(any(), any(Page.class))).thenReturn("[]");

    resource.retrieveAllClientTransactions(CLIENT_ID, uriInfo, null, null);

    verify(context).validateHasReadPermission(ClientApiConstants.CLIENT_CHARGES_RESOURCE_NAME);
    verify(clientTransactionReadPlatformService)
        .retrieveAllTransactions(
            eq(CLIENT_ID), org.mockito.ArgumentMatchers.isA(SearchParameters.class));
  }

  @Test
  void retrieveAllClientTransactions_throwsIfNoPermission() {
    mockClientMapped();
    doThrow(new NoAuthorizationException("no auth")).when(context).validateHasReadPermission(any());

    assertThrows(
        NoAuthorizationException.class,
        () -> resource.retrieveAllClientTransactions(CLIENT_ID, uriInfo, null, null));
    verify(clientTransactionReadPlatformService, never())
        .retrieveAllTransactions(anyLong(), any(SearchParameters.class));
  }

  // --- addNewClientImage ---

  @Test
  void addNewClientImage_checksCreatePermissionAndDelegatesToImageWritePlatformService() {
    mockClientMapped();
    InputStream is = mock(InputStream.class);
    FormDataContentDisposition fileDetails = mock(FormDataContentDisposition.class);
    FormDataBodyPart filePart = mock(FormDataBodyPart.class);

    when(fileDetails.getFileName()).thenReturn("image.png");
    when(filePart.getMediaType()).thenReturn(MediaType.valueOf("image/png"));

    ImageCreateResponse response = new ImageCreateResponse(CLIENT_ID, "image-id");
    when(imageWritePlatformService.createImage(any(ImageCreateRequest.class))).thenReturn(response);

    ImageCreateResponse result =
        resource.addNewClientImage(CLIENT_ID, 123L, is, fileDetails, filePart);

    verify(context).validateHasCreatePermission("CLIENTIMAGE");
    assertNotNull(result);
  }

  @Test
  void addNewClientImage_throwsIfNoCreatePermissionAndDoesNotWrite() {
    mockClientMapped();
    InputStream is = mock(InputStream.class);
    FormDataContentDisposition fileDetails = mock(FormDataContentDisposition.class);
    FormDataBodyPart filePart = mock(FormDataBodyPart.class);

    doThrow(new NoAuthorizationException("no auth"))
        .when(context)
        .validateHasCreatePermission("CLIENTIMAGE");

    assertThrows(
        NoAuthorizationException.class,
        () -> resource.addNewClientImage(CLIENT_ID, 123L, is, fileDetails, filePart));
    verify(imageWritePlatformService, never()).createImage(any());
  }

  // --- deleteClientImage ---

  @Test
  void deleteClientImage_throwsIfNoDeletePermission() {
    mockClientMapped();

    doThrow(new NoAuthorizationException("no auth"))
        .when(context)
        .validateHasDeletePermission("CLIENTIMAGE");

    assertThrows(NoAuthorizationException.class, () -> resource.deleteClientImage(CLIENT_ID));
    verify(imageWritePlatformService, never()).deleteImage(any());
  }
}
