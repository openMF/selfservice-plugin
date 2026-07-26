/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a
 * copy of the MPL was not distributed with this file, You can obtain one at
 * http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.account.service;

import org.apache.fineract.selfservice.account.data.FeeCollectionRequest;
import org.apache.fineract.selfservice.account.data.FeeCollectionResult;

/**
 * Collects the self-service transfer commission in an <b>independent transaction</b>
 * ({@code REQUIRES_NEW}) so that the {@code SavingsAccount} entity is loaded in a
 * fresh JPA persistence context, avoiding the EclipseLink-5006 optimistic-lock
 * conflict that occurs when the same entity is already managed by the caller's
 * transaction.
 *
 * <p>Multi-tenant: the implementation relies on Fineract's thread-bound tenant
 * identifier which is preserved across {@code REQUIRES_NEW} boundaries.
 */
public interface SelfServiceFeeCollectionService {

  /**
   * Calculates and collects the transfer fee.
   *
   * @param request all data needed (no JPA entities cross the boundary)
   * @result outcome; never {@code null}
   * @throws RuntimeException when the internal Fineract transfer fails — the
   *     REQUIRES_NEW transaction is rolled back and the caller decides how to
   *     handle the failure (non-fatal by design).
   */
  FeeCollectionResult collectFee(FeeCollectionRequest request);
}