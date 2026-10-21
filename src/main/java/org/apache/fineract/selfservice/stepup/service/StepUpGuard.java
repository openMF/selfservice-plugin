/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.stepup.service;

import jakarta.servlet.http.HttpServletRequest;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.selfservice.security.service.PlatformSelfServiceSecurityContext;
import org.apache.fineract.selfservice.stepup.domain.StepUpAction;
import org.apache.fineract.selfservice.stepup.exception.StepUpException;
import org.springframework.stereotype.Component;

/**
 * Enforcement point called by the sensitive endpoints (transfers, password change).
 *
 * <p>When step-up is disabled, or the action is not in the tenant's protected list, the guard does
 * nothing, so the API behaves exactly as before. Otherwise the request must carry a valid {@code
 * X-Step-Up-Token} issued for this user, this session, this action and this exact payload; the
 * token is consumed atomically (it works once).
 *
 * <p>Call it inside the caller's transaction, after input validation and business limits and right
 * before the operation takes effect: if the operation then fails and rolls back, the consumption
 * rolls back with it, and an over-limit request never burns an OTP.
 */
@Component
@RequiredArgsConstructor
public class StepUpGuard {

  private final StepUpSettingsProvider settingsProvider;
  private final StepUpChallengeService challengeService;
  private final PlatformSelfServiceSecurityContext securityContext;

  /** {@code true} when step-up is on for this tenant and protects {@code action}. */
  public boolean isProtected(final StepUpAction action) {
    return settingsProvider.current().isProtected(action);
  }

  /**
   * Requires and consumes a valid step-up grant when {@code action} is protected.
   *
   * @param payloadHash computes the digest of the REAL request (never taken from the client); only
   *     evaluated when a check is actually needed
   * @throws StepUpException {@code stepup.required} without a token, {@code
   *     stepup.session.required} for credential-based (non-session) authentication, {@code
   *     stepup.invalid} for any unknown/expired/used/mismatching token
   */
  public void requireIfProtected(
      final StepUpAction action,
      final HttpServletRequest request,
      final Supplier<String> payloadHash) {
    if (!isProtected(action)) {
      return;
    }
    final String sessionHash =
        StepUpRequestSupport.sessionHash(request).orElseThrow(StepUpException::sessionRequired);
    final String grantToken =
        StepUpRequestSupport.grantToken(request)
            .orElseThrow(() -> StepUpException.required(action));
    final Long userId = securityContext.authenticatedSelfServiceUser().getId();
    challengeService.consumeGrant(
        grantToken,
        userId,
        sessionHash,
        action,
        payloadHash.get(),
        StepUpRequestSupport.clientIp(request));
  }
}
