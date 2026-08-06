/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.account.exception;

import org.apache.fineract.infrastructure.core.exception.AbstractPlatformResourceNotFoundException;

/**
 * Thrown when a self-service account transfer transaction cannot be found for the given account
 * and transaction id (and optional transfer type). Multi-tenant safe: lookup runs in the current
 * tenant context.
 */
public class SelfAccountTransferTransactionNotFoundException
    extends AbstractPlatformResourceNotFoundException {

  public SelfAccountTransferTransactionNotFoundException(
      final Long accountId, final String txnId, final String transferType) {
    super(
        "error.msg.self.account.transfer.transaction.not.found",
        "Transfer transaction not found for account "
            + accountId
            + " and txnId "
            + txnId
            + (transferType != null ? " (transferType=" + transferType + ")" : ""),
        accountId,
        txnId,
        transferType);
  }

  public SelfAccountTransferTransactionNotFoundException(
      final Long accountId, final String txnId) {
    this(accountId, txnId, null);
  }
}