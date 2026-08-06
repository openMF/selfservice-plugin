package org.apache.fineract.selfservice.security.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SelfServiceDeviceFingerprintRepository
    extends JpaRepository<SelfServiceDeviceFingerprint, Long> {

  Optional<SelfServiceDeviceFingerprint> findByAppUserIdAndFingerprintHash(
      Long appUserId, String fingerprintHash);

  List<SelfServiceDeviceFingerprint> findByAppUserIdOrderByLastSeenAtDesc(Long appUserId);

  boolean existsByAppUserIdAndFingerprintHash(Long appUserId, String fingerprintHash);
}