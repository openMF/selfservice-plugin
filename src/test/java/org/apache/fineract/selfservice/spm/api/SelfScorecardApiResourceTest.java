/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.spm.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.apache.fineract.infrastructure.core.exception.InvalidJsonException;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.selfservice.security.exception.SelfServiceAccessDeniedException;
import org.apache.fineract.spm.api.ScorecardApiResource;
import org.apache.fineract.spm.data.ScorecardData;
import org.apache.fineract.selfservice.security.guard.SelfServiceOwnershipGuard;
import org.apache.fineract.selfservice.security.service.PlatformSelfServiceSecurityContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SelfScorecardApiResourceTest {

  @Mock private PlatformSelfServiceSecurityContext context;
  @Mock private ScorecardApiResource scorecardApiResource;
  @Mock private SelfServiceOwnershipGuard selfServiceOwnershipGuard;

  private SelfScorecardApiResource resource;

  private static final Long SURVEY_ID = 1L;
  private static final Long CLIENT_ID = 7L;

  @BeforeEach
  void setUp() {
    resource =
        new SelfScorecardApiResource(context, scorecardApiResource, selfServiceOwnershipGuard);
  }

  private static ScorecardData scorecardWithClient(final Long clientId) {
    return ScorecardData.instance(null, null, null, SURVEY_ID, null, clientId);
  }

  @Test
  void createScorecard_withClientId_validatesOwnershipBeforeSaving() {
    ScorecardData data = scorecardWithClient(CLIENT_ID);

    resource.createScorecard(SURVEY_ID, data);

    // The ownership check must run before the scorecard is saved.
    InOrder inOrder = inOrder(selfServiceOwnershipGuard, scorecardApiResource);
    inOrder.verify(selfServiceOwnershipGuard).validateClientOwnership(CLIENT_ID);
    inOrder.verify(scorecardApiResource).createScorecard(SURVEY_ID, data);
  }

  @Test
  void createScorecard_missingClientId_throwsValidationErrorOnClientId() {
    ScorecardData data = scorecardWithClient(null);

    PlatformApiDataValidationException ex =
        assertThrows(
            PlatformApiDataValidationException.class,
            () -> resource.createScorecard(SURVEY_ID, data));

    // The failure is reported on the clientId parameter and nothing is saved.
    verifyNoInteractions(selfServiceOwnershipGuard, scorecardApiResource);
    assertEquals(1, ex.getErrors().size());
    assertEquals("clientId", ex.getErrors().get(0).getParameterName());
  }

  @Test
  void createScorecard_emptyBody_throwsInvalidJsonException() {
    assertThrows(
        InvalidJsonException.class, () -> resource.createScorecard(SURVEY_ID, (ScorecardData) null));

    verifyNoInteractions(selfServiceOwnershipGuard, scorecardApiResource);
  }

  @Test
  void createScorecard_ownershipCheckFails_doesNotSave() {
    ScorecardData data = scorecardWithClient(CLIENT_ID);
    doThrow(new SelfServiceAccessDeniedException(1L, "CLIENT", CLIENT_ID))
        .when(selfServiceOwnershipGuard)
        .validateClientOwnership(CLIENT_ID);

    assertThrows(
        SelfServiceAccessDeniedException.class, () -> resource.createScorecard(SURVEY_ID, data));

    verify(scorecardApiResource, never()).createScorecard(anyLong(), any(ScorecardData.class));
  }
}
