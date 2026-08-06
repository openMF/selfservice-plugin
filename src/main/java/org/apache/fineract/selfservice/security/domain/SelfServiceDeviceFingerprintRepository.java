/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.security.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SelfServiceDeviceFingerprintRepository
    extends JpaRepository<SelfServiceDeviceFingerprint, Long> {

  Optional<SelfServiceDeviceFingerprint> findByAppUserIdAndFingerprintHash(
      Long appUserId, String fingerprintHash);

  List<SelfServiceDeviceFingerprint> findByAppUserIdOrderByLastSeenAtDesc(Long appUserId);
  
  long countByAppUserId(Long appUserId);

  boolean existsByAppUserIdAndFingerprintHash(Long appUserId, String fingerprintHash);
}