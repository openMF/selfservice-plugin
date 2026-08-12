/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.kyc.service;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
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

  /**
   * Returns the KYC snapshot shown on self-service authentication.
   *
   * <p>Selection rules (in order):
   * <ol>
   *   <li>Prefer any row with status {@code Approved} for this client (latest by last_modified /
   *       verification id).</li>
   *   <li>Otherwise take the most recently modified feature status for the client.</li>
   *   <li>Fallback defaults when nothing is stored yet.</li>
   * </ol>
   * This prevents a previous Declined session from masking a later Approved result
   * (re-application / same session status.updated / data.updated).
   */
  @Transactional(readOnly = true)
  public SelfServiceAuthenticatedUserKycData getKycFeatureStatus(final Long clientId) {
    if (clientId == null) {
      return defaultData();
    }

    // 1) Prefer Approved if present
    final Optional<KycFeatureStatus> approved =
        kycFeatureStatusRepository
            .findFirstByKycVerification_ClientIdAndKycStatusOrderByLastModifiedOnUtcDescIdDesc(
                clientId, "Approved");
    if (approved.isPresent()) {
      return toData(approved.get());
    }

    // 2) Latest by last_modified (then id) — covers Declined → Approved same session after in-place update
    final Optional<KycFeatureStatus> latest =
        kycFeatureStatusRepository
            .findFirstByKycVerification_ClientIdOrderByLastModifiedOnUtcDescIdDesc(clientId);
    if (latest.isPresent()) {
      return toData(latest.get());
    }

    // 3) Legacy fallback if repository only has the old method
    return kycFeatureStatusRepository
        .findFirstByKycVerification_ClientIdOrderByKycVerification_IdDesc(clientId)
        .map(this::toData)
        .orElseGet(this::defaultData);
  }

  @Transactional(readOnly = true)
  public SelfServiceAuthenticatedUserKycData getApprovedKycFeatureStatus(final Long clientId) {
    if (clientId == null) {
      return defaultData();
    }
    return kycFeatureStatusRepository
        .findFirstByKycVerification_ClientIdAndKycStatusOrderByLastModifiedOnUtcDescIdDesc(
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