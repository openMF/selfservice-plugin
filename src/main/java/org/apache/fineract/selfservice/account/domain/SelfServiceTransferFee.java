package org.apache.fineract.selfservice.account.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.apache.fineract.infrastructure.core.domain.AbstractPersistableCustom;

@Entity
@Table(name = "m_selfservice_transfer_fees")
@Getter
@Setter
@NoArgsConstructor
public class SelfServiceTransferFee extends AbstractPersistableCustom<Long> {

  @Column(name = "transfer_type", nullable = false)
  private String transferType;

  @Column(name = "currency_code", nullable = false)
  private String currencyCode;

  @Column(name = "transfer_mode")
  private String transferMode;

  @Column(name = "fee_type", nullable = false)
  private String feeType; // FIXED or PERCENTAGE

  @Column(name = "fee_value", nullable = false)
  private BigDecimal feeValue;

  @Column(name = "fee_currency")
  private String feeCurrency;

  @Column(name = "threshold_amount")
  private BigDecimal thresholdAmount;

  @Column(name = "threshold_fee_value")
  private BigDecimal thresholdFeeValue;

  @Column(name = "description")
  private String description;

  @Column(name = "is_active", nullable = false)
  private boolean isActive = true;
}
