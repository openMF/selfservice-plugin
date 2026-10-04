/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.kyc.service;

import java.util.List;
import java.util.Optional;
import org.apache.fineract.kyc.domain.KycFeatureStatus;
import org.apache.fineract.kyc.domain.KycVerification;
import org.apache.fineract.kyc.repository.KycFeatureStatusRepository;
import org.apache.fineract.kyc.repository.KycVerificationRepository;
import org.apache.fineract.selfservice.security.data.SelfServiceAuthenticatedUserKycData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Builds {@code kycValidations} for self-service authentication.
 *
 * <p>Resolution order:
 * <ol>
 *   <li>Feature status with {@code kycStatus = Approved}</li>
 *   <li>Any verification with {@code kycStatus = Approved} (forces all flags true)</li>
 *   <li>Latest feature status for the client</li>
 *   <li>Latest verification status for the client</li>
 *   <li>Default Pending / all false</li>
 * </ol>
 */
@Service
public class KycFeatureStatusReadService {

  private static final Logger LOG = LoggerFactory.getLogger(KycFeatureStatusReadService.class);

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
      LOG.debug("KYC auth: clientId is null → default Pending");
      return defaultData();
    }

    try {
      // 1) Feature-level Approved
      final Optional<KycFeatureStatus> approvedFs =
          kycFeatureStatusRepository
              .findFirstByKycVerification_ClientIdAndKycStatusOrderByLastModifiedOnUtcDescIdDesc(
                  clientId, "Approved");
      if (approvedFs.isPresent()) {
        LOG.debug("KYC auth: clientId={} feature status Approved", clientId);
        return approvedData();
      }

      // 2) Verification-level Approved
      final List<KycVerification> verifications =
          kycVerificationRepository.findByClientIdOrderByCreatedOnUtcDesc(clientId);
      for (final KycVerification v : verifications) {
        if (isApproved(v.getKycStatus())) {
          LOG.debug(
              "KYC auth: clientId={} verification id={} status=Approved → force flags true",
              clientId,
              v.getId());
          return approvedData();
        }
      }

      // 3) Latest feature status (any)
      final Optional<KycFeatureStatus> latestFs =
          kycFeatureStatusRepository
              .findFirstByKycVerification_ClientIdOrderByLastModifiedOnUtcDescIdDesc(clientId);
      if (latestFs.isPresent()) {
        LOG.debug(
            "KYC auth: clientId={} latest feature status={}",
            clientId,
            latestFs.get().getKycStatus());
        return toData(latestFs.get());
      }

      // 4) Latest verification status without feature row
      if (!verifications.isEmpty()) {
        final KycVerification latest = verifications.get(0);
        LOG.debug(
            "KYC auth: clientId={} latest verification status={} (no feature status row)",
            clientId,
            latest.getKycStatus());
        if (isApproved(latest.getKycStatus())) {
          return approvedData();
        }
        return new SelfServiceAuthenticatedUserKycData(
            Boolean.FALSE,
            Boolean.FALSE,
            Boolean.FALSE,
            Boolean.FALSE,
            latest.getKycStatus() != null ? latest.getKycStatus() : "Pending");
      }

      // 5) Legacy feature lookup
      final Optional<KycFeatureStatus> legacy =
          kycFeatureStatusRepository.findFirstByKycVerification_ClientIdOrderByKycVerification_IdDesc(
              clientId);
      if (legacy.isPresent()) {
        return toData(legacy.get());
      }

      LOG.warn(
          "KYC auth: clientId={} has no m_client_kyc_verification / feature_status rows → Pending."
              + " Ensure Didit webhook was posted with header X-Client-Id={}",
          clientId,
          clientId);
      return defaultData();
    } catch (Exception e) {
      LOG.error("KYC auth: failed loading KYC for clientId={}", clientId, e);
      return defaultData();
    }
  }

  @Transactional(readOnly = true)
  public SelfServiceAuthenticatedUserKycData getApprovedKycFeatureStatus(final Long clientId) {
    if (clientId == null) {
      return defaultData();
    }
    return kycFeatureStatusRepository
        .findFirstByKycVerification_ClientIdAndKycStatusOrderByLastModifiedOnUtcDescIdDesc(
            clientId, "Approved")
        .map(fs -> approvedData())
        .orElseGet(this::defaultData);
  }

  /**
   * When DB has no KYC rows but onboarding marks KYC as finished, surface Approved so the app is
   * not blocked after a successful Didit flow that never wrote feature_status (missing X-Client-Id,
   * etc.).
   */
  public SelfServiceAuthenticatedUserKycData getKycFeatureStatusOrOnboardingFallback(
      final Long clientId, final boolean onboardingComplete, final boolean kycStepsComplete) {
    final SelfServiceAuthenticatedUserKycData fromDb = getKycFeatureStatus(clientId);
    if (fromDb != null && isApproved(fromDb.getStatus())) {
      return fromDb;
    }
    // Non-default status from DB (e.g. Declined / In Review) wins over onboarding
    if (fromDb != null
        && StringUtils.hasText(fromDb.getStatus())
        && !"Pending".equalsIgnoreCase(fromDb.getStatus().trim())) {
      return fromDb;
    }
    if (onboardingComplete || kycStepsComplete) {
      LOG.info(
          "KYC auth: clientId={} DB Pending/empty but onboardingComplete={} kycStepsComplete={} → Approved",
          clientId,
          onboardingComplete,
          kycStepsComplete);
      return approvedData();
    }
    return fromDb != null ? fromDb : defaultData();
  }

  private SelfServiceAuthenticatedUserKycData toData(final KycFeatureStatus entity) {
    final String status = entity.getKycStatus();
    if (isApproved(status)) {
      return approvedData();
    }
    return new SelfServiceAuthenticatedUserKycData(
        Boolean.TRUE.equals(entity.getFaceMatches()),
        Boolean.TRUE.equals(entity.getIdVerifications()),
        Boolean.TRUE.equals(entity.getAmlScreenings()),
        Boolean.TRUE.equals(entity.getDecision()),
        StringUtils.hasText(status) ? status : "Pending");
  }

  private static boolean isApproved(final String status) {
    return status != null && "Approved".equalsIgnoreCase(status.trim());
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
