/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.security.guard;

/**
 * Centralized ownership validation guard for all self-service API resources.
 *
 * <p>Every method validates that the authenticated self-service user has ownership of the requested
 * resource. If validation fails, a unified {@link
 * org.apache.fineract.selfservice.security.exception.SelfServiceAccessDeniedException} is thrown
 * (no information disclosure about resource existence or type).
 *
 * <p>All methods are multi-tenant safe: the underlying queries execute on the tenant-routed
 * datasource provided by Fineract's infrastructure.
 *
 * <p>This interface replaces the scattered {@code validateAppuserClientsMapping}, {@code
 * validateAppSelfServiceUserSavingsAccountMapping}, etc. methods that were previously duplicated
 * across API resources.
 */
public interface SelfServiceOwnershipGuard {

  /**
   * Validates that the given client is mapped to the authenticated user.
   *
   * @param clientId the client ID to validate
   * @throws SelfServiceAccessDeniedException if not mapped
   */
  void validateClientOwnership(Long clientId);

  /**
   * Validates that the given savings account belongs to a client mapped to the authenticated user.
   *
   * @param savingsAccountId the savings account ID
   * @throws SelfServiceAccessDeniedException if not owned
   */
  void validateSavingsOwnership(Long savingsAccountId);

  /**
   * Validates that the given loan belongs to a client mapped to the authenticated user.
   *
   * @param loanId the loan ID
   * @throws SelfServiceAccessDeniedException if not owned
   */
  void validateLoanOwnership(Long loanId);

  /**
   * Validates that the given share account belongs to a client mapped to the authenticated user.
   *
   * @param shareAccountId the share account ID
   * @throws SelfServiceAccessDeniedException if not owned
   */
  void validateShareOwnership(Long shareAccountId);

  /**
   * Validates that the given account identifier (IBAN, account number, or numeric ID) resolves to
   * an account owned by the authenticated user.
   *
   * <p>CRITICAL: This is the primary fix for the transfer IDOR vulnerability.
   *
   * @param accountIdentifier the account number, IBAN, or numeric ID
   * @param accountType 2 = savings, 1 = loan
   * @throws SelfServiceAccessDeniedException if not owned
   */
  void validateTransferSourceOwnership(String accountIdentifier, Integer accountType);

  /**
   * Validates that the given beneficiary belongs to the authenticated user.
   *
   * @param beneficiaryId the beneficiary ID
   * @throws SelfServiceAccessDeniedException if not owned
   */
  void validateBeneficiaryOwnership(Long beneficiaryId);

  /**
   * Validates that the given pocket account mapping belongs to the authenticated user.
   *
   * @param mappingId the pocket-account mapping ID
   * @throws SelfServiceAccessDeniedException if not owned
   */
  void validatePocketOwnership(Long mappingId);
}
