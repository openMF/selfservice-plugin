
package org.apache.fineract.selfservice.kyc.service;


import org.apache.fineract.selfservice.security.data.SelfServiceAuthenticatedUserKycData;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import org.apache.fineract.kyc.domain.KycFeatureStatus;
import org.apache.fineract.selfservice.kyc.domain.KycFeatureStatusRepository;

@Service
public class KycFeatureStatusReadService {

    private final KycFeatureStatusRepository kycFeatureStatusRepository;

    public KycFeatureStatusReadService(final KycFeatureStatusRepository kycFeatureStatusRepository) {
        this.kycFeatureStatusRepository = kycFeatureStatusRepository;
    }

    /**
     * Retrieves the KYC feature status for a client's latest verification.
     * Returns a DTO with all booleans set to FALSE if no verification exists.
     *
     * @param clientId the m_client ID
     * @return SelfServiceAuthenticatedUserKycData with the feature flags
     */
    @Transactional(readOnly = true)
    public SelfServiceAuthenticatedUserKycData getKycFeatureStatus(final Long clientId) {
        final Optional<KycFeatureStatus> featureStatusOpt =
                kycFeatureStatusRepository.findLatestByClientId(clientId);

        return featureStatusOpt
                .map(this::toData)
                .orElseGet(this::defaultData);
    }

    /**
     * Retrieves the KYC feature status only if the latest verification is Approved.
     */
    @Transactional(readOnly = true)
    public SelfServiceAuthenticatedUserKycData getApprovedKycFeatureStatus(final Long clientId) {
        final Optional<KycFeatureStatus> featureStatusOpt =
                kycFeatureStatusRepository.findLatestApprovedByClientId(clientId);

        return featureStatusOpt
                .map(this::toData)
                .orElseGet(this::defaultData);
    }

    // ── Mappers ──────────────────────────────────────────────

    private SelfServiceAuthenticatedUserKycData toData(final KycFeatureStatus entity) {
        return new SelfServiceAuthenticatedUserKycData(
                entity.getFaceMatches(),
                entity.getIdVerifications(),
                entity.getAmlScreenings(),
                entity.getDecision()
        );
    }

    private SelfServiceAuthenticatedUserKycData defaultData() {
        // No KYC verification exists — all features are unavailable
        return new SelfServiceAuthenticatedUserKycData(
                Boolean.FALSE,
                Boolean.FALSE,
                Boolean.FALSE,
                Boolean.FALSE
        );
    }
}