package org.apache.fineract.infrastructure.core.domain;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.OffsetDateTime;

@Entity
@Table(name = "m_selfservice_tenant_date_preferences")
@Data
@NoArgsConstructor
public class TenantDatePreference {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, unique = true)
    private String tenantId;

    @Column(name = "default_date_format", length = 50)
    private String defaultDateFormat = "dd-MM-yyyy";

    @Column(name = "default_datetime_format", length = 50)
    private String defaultDatetimeFormat = "yyyy-MM-dd HH:mm:ss";

    @Column(name = "timezone_offset", length = 20)
    private String timezoneOffset = "+00:00";

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = OffsetDateTime.now();
    }
}