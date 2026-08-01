/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.core.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;
import org.apache.fineract.infrastructure.core.domain.TenantDatePreference;

@Repository
public interface TenantDatePreferenceRepository extends JpaRepository<TenantDatePreference, Long> {
    Optional<TenantDatePreference> findByTenantId(String tenantId);
}