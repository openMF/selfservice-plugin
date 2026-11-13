/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.spm.api;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.apache.fineract.infrastructure.core.exception.InvalidJsonException;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.spm.api.ScorecardApiResource;
import org.apache.fineract.spm.data.ScorecardData;
import org.apache.fineract.selfservice.security.guard.SelfServiceOwnershipGuard;
import org.apache.fineract.selfservice.security.service.PlatformSelfServiceSecurityContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
  void createScorecard_withClientId_delegatesAfterOwnershipCheck() {
    ScorecardData data = scorecardWithClient(CLIENT_ID);

    resource.createScorecard(SURVEY_ID, data);

    verify(selfServiceOwnershipGuard).validateClientOwnership(CLIENT_ID);
    verify(scorecardApiResource).createScorecard(SURVEY_ID, data);
  }

  @Test
  void createScorecard_missingClientId_throwsValidationErrorAndSavesNothing() {
    ScorecardData data = scorecardWithClient(null);

    assertThrows(
        PlatformApiDataValidationException.class, () -> resource.createScorecard(SURVEY_ID, data));

    // The failure is reported on the clientId parameter and nothing is saved.
    verify(selfServiceOwnershipGuard, never()).validateClientOwnership(anyLong());
    verify(scorecardApiResource, never()).createScorecard(anyLong(), any(ScorecardData.class));
  }

  @Test
  void createScorecard_emptyBody_throwsInvalidJsonException() {
    assertThrows(
        InvalidJsonException.class, () -> resource.createScorecard(SURVEY_ID, (ScorecardData) null));

    verifyNoInteractions(selfServiceOwnershipGuard, scorecardApiResource);
  }
}
