package org.apache.fineract.selfservice.account.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SelfServiceSinpeEnrollmentRepository
    extends JpaRepository<SelfServiceSinpeEnrollment, Long> {
  Optional<SelfServiceSinpeEnrollment> findByAppSelfServiceUserIdAndMobileNumber(
      Long userId, String mobileNumber);
}
