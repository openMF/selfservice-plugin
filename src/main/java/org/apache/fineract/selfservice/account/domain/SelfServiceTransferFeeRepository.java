package org.apache.fineract.selfservice.account.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SelfServiceTransferFeeRepository
    extends JpaRepository<SelfServiceTransferFee, Long> {

  Optional<SelfServiceTransferFee> findByTransferTypeAndCurrencyCodeAndTransferModeAndIsActiveTrue(
      String transferType, String currencyCode, String transferMode);
}
