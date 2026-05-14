/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.loanaccount.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.notNullValue;

import org.apache.fineract.selfservice.testing.support.SelfServiceIntegrationTestBase;
import org.apache.fineract.selfservice.testing.support.SelfServiceTestUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * End-to-end integration tests for the public loan simulation endpoints (MX-250). These tests run
 * against a real Fineract instance with the self-service plugin deployed via Testcontainers.
 *
 * <p>The test seeds a loan product via direct SQL into the tenant database before running, since
 * the default Fineract demo image starts with an empty {@code m_product_loan} table.
 *
 * <p><strong>Note on error codes:</strong> Fineract's {@code PlatformDomainRuleExceptionMapper}
 * maps {@code AbstractPlatformDomainRuleException} to HTTP 403.
 *
 * <p><strong>Note on schedule calculation:</strong> The core {@code LoanScheduleAssembler} is
 * null-safe for the {@code officeId} parameter when resolving holidays (see FINERACT-2597). When no
 * {@code clientId} is provided (as in public simulation), holiday-based repayment rescheduling is
 * skipped and the schedule is calculated without office-specific holiday adjustments.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SelfPublicLoanSimulationIntegrationTest extends SelfServiceIntegrationTestBase {

  private static final int SEEDED_PRODUCT_ID = 99;

  @BeforeAll
  static void seedLoanProduct() {
    // Seed a minimal loan product into the tenant database.
    executeSqlInPostgres(
        """
        INSERT INTO m_product_loan (
          id, name, short_name, currency_code, currency_digits, currency_multiplesof,
          principal_amount, nominal_interest_rate_per_period, interest_period_frequency_enum,
          annual_nominal_interest_rate, interest_method_enum, interest_calculated_in_period_enum,
          repay_every, repayment_period_frequency_enum, number_of_repayments,
          amortization_method_enum, accounting_type, arrearstolerance_amount,
          loan_transaction_strategy_code, loan_transaction_strategy_name,
          days_in_month_enum, days_in_year_enum, allow_multiple_disbursals
        ) VALUES (
          %s, 'Test Simulation Loan', 'TSL', 'USD', 2, 1,
          10000.00, 12.00, 2,
          12.00, 0, 1,
          1, 2, 12,
          1, 1, 0.00,
          'mifos-standard-strategy', 'Mifos style',
          1, 365, false
        ) ON CONFLICT (id) DO NOTHING;
        """,
        SEEDED_PRODUCT_ID);

    // The assembler calls LoanProduct.getLoanConfigurableAttributes() which NPEs if this row
    // is missing. All booleans default to true (fully configurable).
    executeSqlInPostgres(
        """
        INSERT INTO m_product_loan_configurable_attributes (
          id, loan_product_id,
          amortization_method_enum, interest_method_enum,
          loan_transaction_strategy_code, interest_calculated_in_period_enum,
          arrearstolerance_amount, repay_every, moratorium,
          grace_on_arrears_ageing
        ) VALUES (
          %s, %s,
          true, true,
          true, true,
          true, true, true,
          true
        ) ON CONFLICT (id) DO NOTHING;
        """,
        SEEDED_PRODUCT_ID, SEEDED_PRODUCT_ID);
  }

  // =====================================================================
  // GET /v1/self/loans/simulate/products — Public active loan products
  // =====================================================================

  @Test
  @Order(1)
  @DisplayName("GET /simulate/products without auth returns 200 with non-empty product list")
  void retrieveProducts_withoutAuth_returns200WithProducts() {
    given(SelfServiceTestUtils.requestSpec(getFineractPort()))
        .when()
        .get(SelfServiceTestUtils.SELF_LOAN_SIMULATION_PRODUCTS_PATH)
        .then()
        .statusCode(200)
        .body("size()", greaterThan(0))
        .body("[0].id", notNullValue())
        .body("[0].name", notNullValue())
        .body("[0].currencyCode", notNullValue());
  }

  @Test
  @Order(2)
  @DisplayName("GET /simulate/products returns full product data (not just lookup)")
  void retrieveProducts_returnsFullProductData() {
    given(SelfServiceTestUtils.requestSpec(getFineractPort()))
        .when()
        .get(SelfServiceTestUtils.SELF_LOAN_SIMULATION_PRODUCTS_PATH)
        .then()
        .statusCode(200)
        .body("[0].principal", notNullValue())
        .body("[0].interestRatePerPeriod", notNullValue())
        .body("[0].numberOfRepayments", notNullValue())
        .body("[0].transactionProcessingStrategyCode", notNullValue());
  }

  // =====================================================================
  // GET /v1/self/loans/simulate/template — Public loan template
  // =====================================================================

  @Test
  @Order(3)
  @DisplayName("GET /simulate/template?templateType=individual without auth returns 200")
  void retrieveTemplate_withIndividualType_returns200() {
    given(SelfServiceTestUtils.requestSpec(getFineractPort()))
        .queryParam("templateType", "individual")
        .when()
        .get(SelfServiceTestUtils.SELF_LOAN_SIMULATION_TEMPLATE_PATH)
        .then()
        .statusCode(200);
  }

  @Test
  @Order(4)
  @DisplayName("GET /simulate/template without templateType is rejected (403)")
  void retrieveTemplate_withoutTemplateType_isRejected() {
    given(SelfServiceTestUtils.requestSpec(getFineractPort()))
        .when()
        .get(SelfServiceTestUtils.SELF_LOAN_SIMULATION_TEMPLATE_PATH)
        .then()
        .statusCode(403);
  }

  @Test
  @Order(5)
  @DisplayName("GET /simulate/template?templateType=collateral is rejected (403)")
  void retrieveTemplate_withCollateralType_isRejected() {
    given(SelfServiceTestUtils.requestSpec(getFineractPort()))
        .queryParam("templateType", "collateral")
        .when()
        .get(SelfServiceTestUtils.SELF_LOAN_SIMULATION_TEMPLATE_PATH)
        .then()
        .statusCode(403);
  }

  @Test
  @Order(6)
  @DisplayName("GET /simulate/template?templateType=group is rejected (403)")
  void retrieveTemplate_withGroupType_isRejected() {
    given(SelfServiceTestUtils.requestSpec(getFineractPort()))
        .queryParam("templateType", "group")
        .when()
        .get(SelfServiceTestUtils.SELF_LOAN_SIMULATION_TEMPLATE_PATH)
        .then()
        .statusCode(403);
  }

  // =====================================================================
  // POST /v1/self/loans/simulate?command=calculateLoanSchedule
  // =====================================================================

  @Test
  @Order(7)
  @DisplayName("POST /simulate?command=calculateLoanSchedule returns 200 with schedule")
  void calculateSchedule_validRequest_returns200WithSchedule() {
    // Without clientId, holiday-based rescheduling is skipped (no office context).
    String requestBody =
        String.format(
            """
            {
              "productId": %d,
              "principal": 10000,
              "loanTermFrequency": 12,
              "loanTermFrequencyType": 2,
              "numberOfRepayments": 12,
              "repaymentEvery": 1,
              "repaymentFrequencyType": 2,
              "interestRatePerPeriod": 12,
              "amortizationType": 1,
              "interestType": 0,
              "interestCalculationPeriodType": 1,
              "expectedDisbursementDate": "01 June 2026",
              "transactionProcessingStrategyCode": "mifos-standard-strategy",
              "loanType": "individual",
              "dateFormat": "dd MMMM yyyy",
              "locale": "en"
            }
            """,
            SEEDED_PRODUCT_ID);

    given(SelfServiceTestUtils.requestSpec(getFineractPort()))
        .queryParam("command", "calculateLoanSchedule")
        .body(requestBody)
        .when()
        .post(SelfServiceTestUtils.SELF_LOAN_SIMULATION_PATH)
        .then()
        .statusCode(200)
        .body("periods", notNullValue())
        .body("periods.size()", greaterThan(0));
  }

  // =====================================================================
  // Security constraint tests — these must REJECT
  // =====================================================================

  @Test
  @Order(8)
  @DisplayName("POST /simulate without command param returns 400")
  void calculateSchedule_withoutCommand_returns400() {
    given(SelfServiceTestUtils.requestSpec(getFineractPort()))
        .body("{\"productId\": 1, \"principal\": 10000}")
        .when()
        .post(SelfServiceTestUtils.SELF_LOAN_SIMULATION_PATH)
        .then()
        .statusCode(400);
  }

  @Test
  @Order(9)
  @DisplayName("POST /simulate?command=submit returns 400 (blocked)")
  void calculateSchedule_withSubmitCommand_returns400() {
    given(SelfServiceTestUtils.requestSpec(getFineractPort()))
        .queryParam("command", "submit")
        .body("{\"productId\": 1, \"principal\": 10000}")
        .when()
        .post(SelfServiceTestUtils.SELF_LOAN_SIMULATION_PATH)
        .then()
        .statusCode(400);
  }

  @Test
  @Order(10)
  @DisplayName("POST /simulate with clientId in body returns 400 (blocked)")
  void calculateSchedule_withClientId_returns400() {
    given(SelfServiceTestUtils.requestSpec(getFineractPort()))
        .queryParam("command", "calculateLoanSchedule")
        .body("{\"productId\": 1, \"principal\": 10000, \"clientId\": 42}")
        .when()
        .post(SelfServiceTestUtils.SELF_LOAN_SIMULATION_PATH)
        .then()
        .statusCode(400);
  }

  @Test
  @Order(11)
  @DisplayName("POST /simulate with empty body returns 400")
  void calculateSchedule_withEmptyBody_returns400() {
    given(SelfServiceTestUtils.requestSpec(getFineractPort()))
        .queryParam("command", "calculateLoanSchedule")
        .body("")
        .when()
        .post(SelfServiceTestUtils.SELF_LOAN_SIMULATION_PATH)
        .then()
        .statusCode(400);
  }

  // =====================================================================
  // Authenticated endpoints remain protected
  // =====================================================================

  @Test
  @Order(12)
  @DisplayName("GET /v1/self/loanproducts (authenticated) still requires auth")
  void authenticatedLoanProducts_withoutAuth_returns403() {
    given(SelfServiceTestUtils.requestSpec(getFineractPort()))
        .when()
        .get(SelfServiceTestUtils.SELF_LOAN_PRODUCTS_PATH)
        .then()
        .statusCode(403);
  }

  @Test
  @Order(13)
  @DisplayName("GET /v1/self/loans (authenticated) still requires auth")
  void authenticatedLoans_withoutAuth_returns403() {
    given(SelfServiceTestUtils.requestSpec(getFineractPort()))
        .when()
        .get(SelfServiceTestUtils.SELF_LOANS_PATH)
        .then()
        .statusCode(403);
  }
}
