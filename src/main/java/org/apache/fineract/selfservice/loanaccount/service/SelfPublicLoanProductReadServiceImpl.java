/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.loanaccount.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.domain.JdbcSupport;
import org.apache.fineract.infrastructure.core.service.database.DatabaseSpecificSQLGenerator;
import org.apache.fineract.selfservice.loanaccount.data.SelfPublicLoanProductData;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * Implementation that retrieves active loan products with simulation-relevant fields for public
 * (unauthenticated) self-service endpoints using direct SQL.
 *
 * <p><strong>Why this service exists:</strong> All core loan product read methods call {@code
 * PlatformSecurityContext.authenticatedUser()} either directly or through sub-services (e.g.,
 * {@code ChargeReadPlatformService.retrieveLoanProductCharges()}, {@code
 * RateReadService.retrieveProductLoanRates()}). Using direct SQL with a custom {@link RowMapper}
 * bypasses all auth barriers while returning the exact field set needed for a simulation UI.
 *
 * <p><strong>Core version dependency:</strong> Verified against {@code
 * fineract-provider:1.15.0-SNAPSHOT} (artifact {@code 20260329.095314-5}).
 */
@RequiredArgsConstructor
public class SelfPublicLoanProductReadServiceImpl implements SelfPublicLoanProductReadService {

  private final JdbcTemplate jdbcTemplate;
  private final DatabaseSpecificSQLGenerator sqlGenerator;

  @Override
  public Collection<SelfPublicLoanProductData> retrieveAllActiveLoanProducts() {
    final SimulationLoanProductMapper mapper = new SimulationLoanProductMapper();
    final String sql =
        "SELECT "
            + mapper.schema()
            + " WHERE (lp.close_date IS NULL OR lp.close_date >= "
            + sqlGenerator.currentBusinessDate()
            + ")";

    return jdbcTemplate.query(sql, mapper);
  }

  /**
   * Row mapper that extracts simulation-relevant fields from {@code m_product_loan}. This is a
   * reduced field set compared to the core's full {@code LoanProductMapper} (100+ columns, 8 table
   * joins), but contains all fields needed for a loan simulation UI.
   */
  private static final class SimulationLoanProductMapper
      implements RowMapper<SelfPublicLoanProductData> {

    public String schema() {
      return """
          lp.id as id,
          lp.name as name,
          lp.short_name as shortName,
          lp.description as description,
          lp.currency_code as currencyCode,
          lp.currency_digits as currencyDigits,
          lp.currency_multiplesof as inMultiplesOf,
          curr.name as currencyName,
          curr.internationalized_name_code as currencyNameCode,
          curr.display_symbol as currencyDisplaySymbol,
          lp.principal_amount as principal,
          lp.min_principal_amount as minPrincipal,
          lp.max_principal_amount as maxPrincipal,
          lp.nominal_interest_rate_per_period as interestRatePerPeriod,
          lp.min_nominal_interest_rate_per_period as minInterestRatePerPeriod,
          lp.max_nominal_interest_rate_per_period as maxInterestRatePerPeriod,
          lp.annual_nominal_interest_rate as annualInterestRate,
          lp.interest_period_frequency_enum as interestRatePerPeriodFreq,
          lp.interest_method_enum as interestMethod,
          lp.interest_calculated_in_period_enum as interestCalculationInPeriodMethod,
          lp.repay_every as repaidEvery,
          lp.repayment_period_frequency_enum as repaymentPeriodFrequency,
          lp.number_of_repayments as numberOfRepayments,
          lp.min_number_of_repayments as minNumberOfRepayments,
          lp.max_number_of_repayments as maxNumberOfRepayments,
          lp.amortization_method_enum as amortizationMethod,
          lp.arrearstolerance_amount as tolerance,
          lp.accounting_type as accountingType,
          lp.loan_transaction_strategy_code as transactionStrategyCode,
          lp.loan_transaction_strategy_name as transactionStrategyName,
          lp.days_in_month_enum as daysInMonth,
          lp.days_in_year_enum as daysInYear,
          lp.allow_multiple_disbursals as multiDisburseLoan,
          lp.start_date as startDate,
          lp.close_date as closeDate
          FROM m_product_loan lp
          JOIN m_currency curr ON curr.code = lp.currency_code
          """;
    }

    @Override
    public SelfPublicLoanProductData mapRow(ResultSet rs, int rowNum) throws SQLException {
      return SelfPublicLoanProductData.builder()
          .id(rs.getLong("id"))
          .name(rs.getString("name"))
          .shortName(rs.getString("shortName"))
          .description(rs.getString("description"))
          .currencyCode(rs.getString("currencyCode"))
          .currencyName(rs.getString("currencyName"))
          .currencyDisplaySymbol(rs.getString("currencyDisplaySymbol"))
          .currencyDigits(JdbcSupport.getInteger(rs, "currencyDigits"))
          .currencyInMultiplesOf(JdbcSupport.getInteger(rs, "inMultiplesOf"))
          .principal(rs.getBigDecimal("principal"))
          .minPrincipal(rs.getBigDecimal("minPrincipal"))
          .maxPrincipal(rs.getBigDecimal("maxPrincipal"))
          .interestRatePerPeriod(rs.getBigDecimal("interestRatePerPeriod"))
          .minInterestRatePerPeriod(rs.getBigDecimal("minInterestRatePerPeriod"))
          .maxInterestRatePerPeriod(rs.getBigDecimal("maxInterestRatePerPeriod"))
          .annualInterestRate(rs.getBigDecimal("annualInterestRate"))
          .interestRatePerPeriodFrequencyType(
              JdbcSupport.getInteger(rs, "interestRatePerPeriodFreq"))
          .interestType(JdbcSupport.getInteger(rs, "interestMethod"))
          .interestCalculationPeriodType(
              JdbcSupport.getInteger(rs, "interestCalculationInPeriodMethod"))
          .numberOfRepayments(JdbcSupport.getInteger(rs, "numberOfRepayments"))
          .minNumberOfRepayments(JdbcSupport.getInteger(rs, "minNumberOfRepayments"))
          .maxNumberOfRepayments(JdbcSupport.getInteger(rs, "maxNumberOfRepayments"))
          .repaymentEvery(JdbcSupport.getInteger(rs, "repaidEvery"))
          .repaymentFrequencyType(JdbcSupport.getInteger(rs, "repaymentPeriodFrequency"))
          .amortizationType(JdbcSupport.getInteger(rs, "amortizationMethod"))
          .transactionProcessingStrategyCode(rs.getString("transactionStrategyCode"))
          .transactionProcessingStrategyName(rs.getString("transactionStrategyName"))
          .daysInMonthType(JdbcSupport.getInteger(rs, "daysInMonth"))
          .daysInYearType(JdbcSupport.getInteger(rs, "daysInYear"))
          .multiDisburseLoan(rs.getObject("multiDisburseLoan", Boolean.class))
          .accountingType(JdbcSupport.getInteger(rs, "accountingType"))
          .inArrearsTolerance(rs.getBigDecimal("tolerance"))
          .startDate(JdbcSupport.getLocalDate(rs, "startDate"))
          .closeDate(JdbcSupport.getLocalDate(rs, "closeDate"))
          .build();
    }
  }
}
