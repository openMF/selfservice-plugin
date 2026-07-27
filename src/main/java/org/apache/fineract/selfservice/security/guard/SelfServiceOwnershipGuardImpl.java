/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.security.guard;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.selfservice.client.service.AppSelfServiceUserClientMapperReadService;
import org.apache.fineract.selfservice.loanaccount.service.AppuserLoansMapperReadService;
import org.apache.fineract.selfservice.savings.service.AppuserSavingsMapperReadService;
import org.apache.fineract.selfservice.security.audit.SelfServiceAccessAudit;
import org.apache.fineract.selfservice.security.audit.SelfServiceAccessAuditDto;
import org.apache.fineract.selfservice.security.audit.SelfServiceAccessAuditService;
import org.apache.fineract.selfservice.security.exception.SelfServiceAccessDeniedException;
import org.apache.fineract.selfservice.security.service.PlatformSelfServiceSecurityContext;
import org.apache.fineract.selfservice.shareaccounts.service.AppUserShareAccountsMapperReadPlatformService;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Centralized implementation of {@link SelfServiceOwnershipGuard}.
 *
 * <p>SECURITY DESIGN PRINCIPLES:
 *
 * <ul>
 *   <li>Single point of enforcement — all API resources delegate here
 *   <li>Uniform error response — no information disclosure
 *   <li>Rate-limiting — blocks brute-force enumeration after N denied attempts
 *   <li>Audit trail — every check (granted/denied) is recorded asynchronously
 *   <li>Multi-tenant — all SQL runs on tenant-routed JdbcTemplate
 *   <li>Fail-closed — if ownership cannot be confirmed, access is DENIED
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SelfServiceOwnershipGuardImpl implements SelfServiceOwnershipGuard {

  private final PlatformSelfServiceSecurityContext securityContext;
  private final AppSelfServiceUserClientMapperReadService clientMapperService;
  private final AppuserSavingsMapperReadService savingsMapperService;
  private final AppuserLoansMapperReadService loansMapperService;
  private final AppUserShareAccountsMapperReadPlatformService shareMapperService;
  private final SelfServiceAccessAuditService auditService;
  private final JdbcTemplate jdbcTemplate;

  // =====================================================================
  // CLIENT OWNERSHIP
  // =====================================================================
  @Override
  public void validateClientOwnership(final Long clientId) {
    final AppSelfServiceUser user = securityContext.authenticatedSelfServiceUser();
    enforceRateLimit(user, SelfServiceAccessAudit.ResourceType.CLIENT);

    if (clientId == null) {
      denyAndThrow(user, SelfServiceAccessAudit.ResourceType.CLIENT, null, "null");
    }

    final boolean isMapped =
        clientMapperService.isClientMappedToSelfServiceUser(clientId, user.getId());
    if (!isMapped) {
      denyAndThrow(
          user, SelfServiceAccessAudit.ResourceType.CLIENT, clientId, String.valueOf(clientId));
    }

    auditGranted(user, SelfServiceAccessAudit.ResourceType.CLIENT, clientId);
  }

  // =====================================================================
  // SAVINGS OWNERSHIP
  // =====================================================================
  @Override
  public void validateSavingsOwnership(final Long savingsAccountId) {
    final AppSelfServiceUser user = securityContext.authenticatedSelfServiceUser();
    enforceRateLimit(user, SelfServiceAccessAudit.ResourceType.SAVINGS_ACCOUNT);

    if (savingsAccountId == null) {
      denyAndThrow(user, SelfServiceAccessAudit.ResourceType.SAVINGS_ACCOUNT, null, "null");
    }

    final boolean isMapped =
        savingsMapperService.isSavingsMappedToUser(savingsAccountId, user.getId());
    if (!isMapped) {
      denyAndThrow(
          user,
          SelfServiceAccessAudit.ResourceType.SAVINGS_ACCOUNT,
          savingsAccountId,
          String.valueOf(savingsAccountId));
    }

    auditGranted(user, SelfServiceAccessAudit.ResourceType.SAVINGS_ACCOUNT, savingsAccountId);
  }

  // =====================================================================
  // LOAN OWNERSHIP
  // =====================================================================
  @Override
  public void validateLoanOwnership(final Long loanId) {
    final AppSelfServiceUser user = securityContext.authenticatedSelfServiceUser();
    enforceRateLimit(user, SelfServiceAccessAudit.ResourceType.LOAN_ACCOUNT);

    if (loanId == null) {
      denyAndThrow(user, SelfServiceAccessAudit.ResourceType.LOAN_ACCOUNT, null, "null");
    }

    final boolean isMapped = loansMapperService.isLoanMappedToUser(loanId, user.getId());
    if (!isMapped) {
      denyAndThrow(
          user, SelfServiceAccessAudit.ResourceType.LOAN_ACCOUNT, loanId, String.valueOf(loanId));
    }

    auditGranted(user, SelfServiceAccessAudit.ResourceType.LOAN_ACCOUNT, loanId);
  }

  // =====================================================================
  // SHARE ACCOUNT OWNERSHIP
  // =====================================================================
  @Override
  public void validateShareOwnership(final Long shareAccountId) {
    final AppSelfServiceUser user = securityContext.authenticatedSelfServiceUser();
    enforceRateLimit(user, SelfServiceAccessAudit.ResourceType.SHARE_ACCOUNT);

    if (shareAccountId == null) {
      denyAndThrow(user, SelfServiceAccessAudit.ResourceType.SHARE_ACCOUNT, null, "null");
    }

    final boolean isMapped =
        shareMapperService.isShareAccountsMappedToUser(shareAccountId, user.getId());
    if (!isMapped) {
      denyAndThrow(
          user,
          SelfServiceAccessAudit.ResourceType.SHARE_ACCOUNT,
          shareAccountId,
          String.valueOf(shareAccountId));
    }

    auditGranted(user, SelfServiceAccessAudit.ResourceType.SHARE_ACCOUNT, shareAccountId);
  }

  // =====================================================================
  // TRANSFER SOURCE OWNERSHIP (CRITICAL FIX)
  // =====================================================================
  @Override
  public void validateTransferSourceOwnership(
      final String accountIdentifier, final Integer accountType) {
    final AppSelfServiceUser user = securityContext.authenticatedSelfServiceUser();
    enforceRateLimit(user, SelfServiceAccessAudit.ResourceType.TRANSFER_SOURCE);

    if (StringUtils.isBlank(accountIdentifier)) {
      denyAndThrow(user, SelfServiceAccessAudit.ResourceType.TRANSFER_SOURCE, null, "blank");
    }

    // Resolve the account identifier to an internal account ID
    final Long resolvedAccountId =
        resolveAccountIdForOwnershipCheck(accountIdentifier, accountType);

    if (resolvedAccountId == null) {
      denyAndThrow(
          user, SelfServiceAccessAudit.ResourceType.TRANSFER_SOURCE, null, accountIdentifier);
    }

    // Validate ownership based on account type
    final boolean isOwned;
    if (accountType != null && accountType == 1) {
      // Loan account
      isOwned = loansMapperService.isLoanMappedToUser(resolvedAccountId, user.getId());
    } else {
      // Default: savings account (type 2)
      isOwned = savingsMapperService.isSavingsMappedToUser(resolvedAccountId, user.getId());
    }

    if (!isOwned) {
      denyAndThrow(
          user,
          SelfServiceAccessAudit.ResourceType.TRANSFER_SOURCE,
          resolvedAccountId,
          accountIdentifier);
    }

    auditGranted(user, SelfServiceAccessAudit.ResourceType.TRANSFER_SOURCE, resolvedAccountId);
  }

  // =====================================================================
  // BENEFICIARY OWNERSHIP
  // =====================================================================
  @Override
  public void validateBeneficiaryOwnership(final Long beneficiaryId) {
    final AppSelfServiceUser user = securityContext.authenticatedSelfServiceUser();
    enforceRateLimit(user, SelfServiceAccessAudit.ResourceType.BENEFICIARY);

    if (beneficiaryId == null) {
      denyAndThrow(user, SelfServiceAccessAudit.ResourceType.BENEFICIARY, null, "null");
    }

    // Query: does this beneficiary belong to the authenticated user?
    final Boolean isOwned =
        jdbcTemplate.queryForObject(
            """
                SELECT CASE WHEN (COUNT(*) > 0) THEN TRUE ELSE FALSE END
                FROM m_selfservice_beneficiaries_tpt
                WHERE id = ? AND app_selfservice_user_id = ? AND is_active = TRUE
                """,
            Boolean.class,
            beneficiaryId,
            user.getId());

    if (isOwned == null || !isOwned) {
      denyAndThrow(
          user,
          SelfServiceAccessAudit.ResourceType.BENEFICIARY,
          beneficiaryId,
          String.valueOf(beneficiaryId));
    }

    auditGranted(user, SelfServiceAccessAudit.ResourceType.BENEFICIARY, beneficiaryId);
  }

  // =====================================================================
  // POCKET OWNERSHIP
  // =====================================================================
  @Override
  public void validatePocketOwnership(final Long mappingId) {
    final AppSelfServiceUser user = securityContext.authenticatedSelfServiceUser();
    enforceRateLimit(user, SelfServiceAccessAudit.ResourceType.POCKET);

    if (mappingId == null) {
      denyAndThrow(user, SelfServiceAccessAudit.ResourceType.POCKET, null, "null");
    }

    final Boolean isOwned =
        jdbcTemplate.queryForObject(
            """
                SELECT CASE WHEN (COUNT(*) > 0) THEN TRUE ELSE FALSE END
                FROM m_pocket_accounts_mapping pam
                JOIN m_pocket p ON p.id = pam.pocket_id
                WHERE pam.id = ? AND p.app_user_id = ?
                """,
            Boolean.class,
            mappingId,
            user.getId());

    if (isOwned == null || !isOwned) {
      denyAndThrow(
          user, SelfServiceAccessAudit.ResourceType.POCKET, mappingId, String.valueOf(mappingId));
    }

    auditGranted(user, SelfServiceAccessAudit.ResourceType.POCKET, mappingId);
  }

  // =====================================================================
  // PRIVATE HELPERS
  // =====================================================================

  /**
   * Resolves an account identifier (numeric ID, account number, or IBAN/external ID) to an internal
   * Fineract account ID for ownership validation. Returns null if the account cannot be found
   * (fail-closed).
   */
  private Long resolveAccountIdForOwnershipCheck(
      final String accountIdentifier, final Integer accountType) {
    try {
      final String trimmed = accountIdentifier.trim();

      // Try as numeric ID first
      try {
        return Long.valueOf(trimmed);
      } catch (NumberFormatException ignored) {
        // Not numeric — resolve by account number or external ID
      }

      // Resolve by account number (savings)
      if (accountType == null || accountType == 2) {
        Long id =
            jdbcTemplate.query(
                "SELECT id FROM m_savings_account WHERE account_no = ? OR external_id = ?",
                rs -> rs.next() ? rs.getLong(1) : null,
                trimmed,
                trimmed);
        if (id != null) {
          return id;
        }
      }

      // Resolve by account number (loan)
      if (accountType != null && accountType == 1) {
        Long id =
            jdbcTemplate.query(
                "SELECT id FROM m_loan WHERE account_no = ? OR external_id = ?",
                rs -> rs.next() ? rs.getLong(1) : null,
                trimmed,
                trimmed);
        if (id != null) {
          return id;
        }
      }

      // Fallback: try both tables
      Long savingsId =
          jdbcTemplate.query(
              "SELECT id FROM m_savings_account WHERE account_no = ? OR external_id = ?",
              rs -> rs.next() ? rs.getLong(1) : null,
              trimmed,
              trimmed);
      if (savingsId != null) {
        return savingsId;
      }

      Long loanId =
          jdbcTemplate.query(
              "SELECT id FROM m_loan WHERE account_no = ? OR external_id = ?",
              rs -> rs.next() ? rs.getLong(1) : null,
              trimmed,
              trimmed);
      return loanId;

    } catch (Exception e) {
      log.warn(
          "Failed to resolve account identifier '{}' for ownership check (fail-closed)",
          accountIdentifier,
          e);
      return null; // fail-closed
    }
  }

  /**
   * Enforces rate-limiting: if the user has too many recent denied attempts, block immediately
   * without even checking ownership.
   */
  private void enforceRateLimit(
      final AppSelfServiceUser user, final SelfServiceAccessAudit.ResourceType resourceType) {
    if (auditService.isRateLimitExceeded(user.getId(), resourceType)
        || auditService.isGlobalRateLimitExceeded(user.getId())) {
      log.warn(
          "SECURITY: Rate limit exceeded for user={} resourceType={}. Blocking.",
          user.getUsername(),
          resourceType);
      throw new SelfServiceAccessDeniedException(user.getId(), resourceType.name(), (Long) null);
    }
  }

  /** Records a DENIED audit event and throws the unified exception. */
  private void denyAndThrow(
      final AppSelfServiceUser user,
      final SelfServiceAccessAudit.ResourceType resourceType,
      final Long resourceId,
      final String resourceIdentifier) {
    auditService.recordAccess(
        SelfServiceAccessAuditDto.builder()
            .appUserId(user.getId())
            .username(user.getUsername())
            .resourceType(resourceType)
            .resourceId(resourceId)
            .resourceIdentifier(resourceIdentifier)
            .accessResult(SelfServiceAccessAudit.AccessResult.DENIED)
            .endpoint(extractEndpoint())
            .httpMethod(extractHttpMethod())
            .ipAddress(extractClientIp())
            .build());

    throw new SelfServiceAccessDeniedException(user.getId(), resourceType.name(), resourceId);
  }

  /** Records a GRANTED audit event (async, non-blocking). */
  private void auditGranted(
      final AppSelfServiceUser user,
      final SelfServiceAccessAudit.ResourceType resourceType,
      final Long resourceId) {
    auditService.recordAccess(
        SelfServiceAccessAuditDto.builder()
            .appUserId(user.getId())
            .username(user.getUsername())
            .resourceType(resourceType)
            .resourceId(resourceId)
            .accessResult(SelfServiceAccessAudit.AccessResult.GRANTED)
            .endpoint(extractEndpoint())
            .httpMethod(extractHttpMethod())
            .ipAddress(extractClientIp())
            .build());
  }

  private String extractEndpoint() {
    try {
      ServletRequestAttributes attrs =
          (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
      if (attrs != null) {
        return attrs.getRequest().getRequestURI();
      }
    } catch (Exception ignored) {
    }
    return "unknown";
  }

  private String extractHttpMethod() {
    try {
      ServletRequestAttributes attrs =
          (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
      if (attrs != null) {
        return attrs.getRequest().getMethod();
      }
    } catch (Exception ignored) {
    }
    return "UNKNOWN";
  }

  private String extractClientIp() {
    try {
      ServletRequestAttributes attrs =
          (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
      if (attrs != null) {
        HttpServletRequest req = attrs.getRequest();
        String xff = req.getHeader("X-Forwarded-For");
        if (StringUtils.isNotBlank(xff)) {
          return xff.split(",")[0].trim();
        }
        return req.getRemoteAddr();
      }
    } catch (Exception ignored) {
    }
    return "unknown";
  }
}
