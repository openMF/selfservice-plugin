package org.apache.fineract.infrastructure.core.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;
import org.apache.fineract.infrastructure.core.domain.TenantDatePreference;

@Repository
public interface TenantDatePreferenceRepository extends JpaRepository<TenantDatePreference, Long> {
    Optional<TenantDatePreference> findByTenantId(String tenantId);
}