/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.loanaccount.service;

import java.util.Collection;
import org.apache.fineract.selfservice.loanaccount.data.SelfPublicLoanProductData;

/**
 * Reads loan product data for public (unauthenticated) self-service endpoints.
 *
 * <p>This service bypasses all core Fineract service-layer auth checks by using direct SQL queries.
 * Only active products are returned, with simulation-relevant fields only.
 *
 * <p>Bytecode analysis performed against {@code fineract-provider:1.15.0-SNAPSHOT} (artifact {@code
 * 20260329.095314-5}).
 */
public interface SelfPublicLoanProductReadService {

  /**
   * Retrieves all active loan products with simulation-relevant fields (currency, principal ranges,
   * interest rates, repayment configuration, etc.).
   *
   * <p>Active products are those where {@code close_date IS NULL OR close_date >=
   * currentBusinessDate}.
   *
   * @return a collection of {@link SelfPublicLoanProductData} for active products
   */
  Collection<SelfPublicLoanProductData> retrieveAllActiveLoanProducts();
}
