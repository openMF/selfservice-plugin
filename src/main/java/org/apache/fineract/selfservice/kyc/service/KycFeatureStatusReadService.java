/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.kyc.service;

import java.util.Optional;
import org.apache.fineract.kyc.domain.KycFeatureStatus;
import org.apache.fineract.kyc.domain.KycVerification;
import org.apache.fineract.kyc.repository.KycFeatureStatusRepository;
import org.apache.fineract.kyc.repository.KycVerificationRepository;
import org.apache.fineract.selfservice.security.data.SelfServiceAuthenticatedUserKycData;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Reads KYC feature status for the self-service authentication response ({@code kycValidations}).
 *
 * <p>Selection rules (in order):
 * <ol>
 *   <li>Prefer any feature-status row with {@code kycStatus = Approved} (latest by last_modified).</li>
 *   <li>Prefer any verification with {@code kycStatus = Approved} and use its feature status.</li>
 *   <li>Otherwise the most recently modified feature status for the client.</li>
 *   <li>Fallback defaults when nothing is stored yet.</li>
 * </ol>
 *
 * <p>When the effective status is {@code Approved}, all boolean flags in the auth payload are
 * forced to {@code true} so the client UI does not show stale false/Pending values.
 */
@Service
public class KycFeatureStatusReadService {

  private final KycFeatureStatusRepository kycFeatureStatusRepository;
  private final KycVerificationRepository kycVerificationRepository;

  public KycFeatureStatusReadService(
      final KycFeatureStatusRepository kycFeatureStatusRepository,
      final KycVerificationRepository kycVerificationRepository) {
    this.kycFeatureStatusRepository = kycFeatureStatusRepository;
    this.kycVerificationRepository = kycVerificationRepository;
  }

  @Transactional(readOnly = true)
  public SelfServiceAuthenticatedUserKycData getKycFeatureStatus(final Long clientId) {
    if (clientId == null) {
      return defaultData();
    }

    // 1) Feature-level Approved
    final Optional<KycFeatureStatus> approvedFs =
        kycFeatureStatusRepository
            .findFirstByKycVerification_ClientIdAndKycStatusOrderByLastModifiedOnUtcDescIdDesc(
                clientId, "Approved");
    if (approvedFs.isPresent()) {
      return toData(approvedFs.get());
    }

    // 2) Verification-level Approved (covers cases where feature flags lagged behind status)
    final Optional<KycVerification> approvedVerification =
        kycVerificationRepository.findByClientIdOrderByCreatedOnUtcDesc(clientId).stream()
            .filter(v -> v.getKycStatus() != null && "Approved".equalsIgnoreCase(v.getKycStatus().trim()))
            .findFirst();
    if (approvedVerification.isPresent()) {
      final KycVerification v = approvedVerification.get();
      if (v.getFeatureStatus() != null) {
        return toData(v.getFeatureStatus(), "Approved");
      }
      return approvedData();
    }

    // 3) Latest feature status
    final Optional<KycFeatureStatus> latest =
        kycFeatureStatusRepository
            .findFirstByKycVerification_ClientIdOrderByLastModifiedOnUtcDescIdDesc(clientId);
    if (latest.isPresent()) {
      return toData(latest.get());
    }

    // 4) Legacy
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
    return toData(entity, entity.getKycStatus());
  }

  private SelfServiceAuthenticatedUserKycData toData(
      final KycFeatureStatus entity, final String statusOverride) {
    final String status =
        StringUtils.hasText(statusOverride) ? statusOverride : entity.getKycStatus();
    if (status != null && "Approved".equalsIgnoreCase(status.trim())) {
      return approvedData();
    }
    return new SelfServiceAuthenticatedUserKycData(
        Boolean.TRUE.equals(entity.getFaceMatches()),
        Boolean.TRUE.equals(entity.getIdVerifications()),
        Boolean.TRUE.equals(entity.getAmlScreenings()),
        Boolean.TRUE.equals(entity.getDecision()),
        status != null ? status : "Pending");
  }

  private SelfServiceAuthenticatedUserKycData approvedData() {
    return new SelfServiceAuthenticatedUserKycData(
        Boolean.TRUE, Boolean.TRUE, Boolean.TRUE, Boolean.TRUE, "Approved");
  }

  private SelfServiceAuthenticatedUserKycData defaultData() {
    return new SelfServiceAuthenticatedUserKycData(
        Boolean.FALSE, Boolean.FALSE, Boolean.FALSE, Boolean.FALSE, "Pending");
  }
}
