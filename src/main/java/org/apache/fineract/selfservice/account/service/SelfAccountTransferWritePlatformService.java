/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.account.service;

import jakarta.servlet.http.HttpServletRequest;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.selfservice.account.data.AccountTransferConfirmRequest;
import org.apache.fineract.selfservice.account.data.AccountTransferPrepareRequest;
import org.apache.fineract.selfservice.account.data.ResendOtpRequest;

public interface SelfAccountTransferWritePlatformService {

  Object prepareTransfer(AccountTransferPrepareRequest request);

  Object quoteTransfer(AccountTransferPrepareRequest request);

  Object confirmTransfer(AccountTransferConfirmRequest request, HttpServletRequest httpRequest);

  Object resendTransferOtp(ResendOtpRequest resendRequest, HttpServletRequest httpRequest);

  /** Pending transfers of the ENTITY clients where the caller is an active signatory. */
  Object retrievePendingApprovals();

  /**
   * Second signature on a held transfer: the caller must be an active signatory of the client other
   * than the initiator, with a step-up token for APPROVE_SECOND_SIGNATURE bound to {@code
   * approvalId}. Executes the initiator's transfer at most once.
   */
  Object approveTransfer(Long approvalId, HttpServletRequest httpRequest);

  /** Closes a held transfer without moving money. Same signatory rules as approval, no token. */
  Object rejectTransfer(Long approvalId);

  CommandProcessingResult createTransfer(
      String type, String apiRequestBodyAsJson, HttpServletRequest httpRequest);
}
