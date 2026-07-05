package org.apache.fineract.selfservice.account.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import org.apache.fineract.infrastructure.core.domain.AbstractPersistableCustom;

@Entity
@Table(name = "m_selfservice_sinpe_enrollment")
public class SelfServiceSinpeEnrollment extends AbstractPersistableCustom<Long> {

    @Column(name = "app_selfservice_user_id", nullable = false)
    private Long appSelfServiceUserId;

    @Column(name = "client_id", nullable = false)
    private Long clientId;

    @Column(name = "mobile_number", length = 20, nullable = false)
    private String mobileNumber;

    @Column(name = "is_verified", nullable = false)
    private boolean isVerified = false;

    @Column(name = "verified_on")
    private LocalDateTime verifiedOn;

    protected SelfServiceSinpeEnrollment() {}

    public SelfServiceSinpeEnrollment(Long appSelfServiceUserId, Long clientId, String mobileNumber) {
        this.appSelfServiceUserId = appSelfServiceUserId;
        this.clientId = clientId;
        this.mobileNumber = mobileNumber;
    }

    public void markAsVerified() {
        this.isVerified = true;
        this.verifiedOn = LocalDateTime.now();
    }

    // Getters
    public Long getAppSelfServiceUserId() { return appSelfServiceUserId; }
    public String getMobileNumber() { return mobileNumber; }
    public boolean isVerified() { return isVerified; }
}