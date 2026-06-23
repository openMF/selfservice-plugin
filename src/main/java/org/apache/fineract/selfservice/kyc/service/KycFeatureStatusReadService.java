/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.kyc.service;

import org.apache.fineract.kyc.domain.KycFeatureStatus;
import org.apache.fineract.kyc.repository.KycFeatureStatusRepository;
import org.apache.fineract.selfservice.security.data.SelfServiceAuthenticatedUserKycData;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KycFeatureStatusReadService {

  private final KycFeatureStatusRepository kycFeatureStatusRepository;

  public KycFeatureStatusReadService(final KycFeatureStatusRepository kycFeatureStatusRepository) {
    this.kycFeatureStatusRepository = kycFeatureStatusRepository;
  }

  @Transactional(readOnly = true)
  public SelfServiceAuthenticatedUserKycData getKycFeatureStatus(final Long clientId) {
    return kycFeatureStatusRepository
        .findFirstByKycVerification_ClientIdOrderByKycVerification_IdDesc(clientId)
        .map(this::toData)
        .orElseGet(this::defaultData);
  }

  @Transactional(readOnly = true)
  public SelfServiceAuthenticatedUserKycData getApprovedKycFeatureStatus(final Long clientId) {
    return kycFeatureStatusRepository
        .findFirstByKycVerification_ClientIdAndKycVerification_KycStatusOrderByKycVerification_IdDesc(
            clientId, "Approved")
        .map(this::toData)
        .orElseGet(this::defaultData);
  }

  private SelfServiceAuthenticatedUserKycData toData(final KycFeatureStatus entity) {
    return new SelfServiceAuthenticatedUserKycData(
        entity.getFaceMatches(),
        entity.getIdVerifications(),
        entity.getAmlScreenings(),
        entity.getDecision(),
        entity.getKycStatus());
  }

  private SelfServiceAuthenticatedUserKycData defaultData() {
    return new SelfServiceAuthenticatedUserKycData(
        Boolean.FALSE, Boolean.FALSE, Boolean.FALSE, Boolean.FALSE, "In Review");
  }
}
