/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.account.service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResultBuilder;
import org.apache.fineract.infrastructure.core.service.ExternalIdFactory;
import org.apache.fineract.portfolio.account.PortfolioAccountType;
import org.apache.fineract.portfolio.account.data.AccountTransferDTO;
import org.apache.fineract.portfolio.account.service.AccountTransfersWritePlatformService;
import org.apache.fineract.portfolio.client.domain.Client;
import org.apache.fineract.selfservice.account.data.AccountTransferConfirmRequest;
import org.apache.fineract.selfservice.security.service.PlatformSelfServiceSecurityContext;
import org.apache.fineract.selfservice.useradministration.domain.AppSelfServiceUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class SelfAccountTransferWritePlatformServiceImpl
    implements SelfAccountTransferWritePlatformService {

  private final AccountTransfersWritePlatformService accountTransfersWritePlatformService;
  private final PlatformSelfServiceSecurityContext context;
  private final ExternalIdFactory externalIdFactory;

  @Override
  @Transactional
  public CommandProcessingResult executeInternalTransfer(AccountTransferConfirmRequest request) {
    AppSelfServiceUser user = context.authenticatedSelfServiceUser();
    Client client = user.getAppUserClientMappings().iterator().next().getClient();

    // Parse the transfer date
    LocalDate transferDate =
        LocalDate.parse(request.getTransferDate(), DateTimeFormatter.ofPattern("yyyy-MM-dd"));

    // Create DateTimeFormatter for the DTO
    DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd").withLocale(Locale.ENGLISH);

    // Generate external ID for the transaction
    var txnExternalId = externalIdFactory.create();

    // Build the AccountTransferDTO object required by Fineract
    AccountTransferDTO transferDTO =
        new AccountTransferDTO(
            transferDate, // transactionDate
            request.getTransferAmount(), // transactionAmount
            PortfolioAccountType.fromInt(request.getFromAccountType()), // fromAccountType
            PortfolioAccountType.fromInt(request.getToAccountType()), // toAccountType
            Long.parseLong(request.getFromAccountId()), // fromAccountId
            Long.parseLong(request.getToAccountId()), // toAccountId
            request.getTransferDescription(), // description
            Locale.ENGLISH, // locale
            fmt, // fmt (DateTimeFormatter)
            null, // paymentDetail
            null, // fromTransferType
            null, // toTransferType
            null, // chargeId
            null, // loanInstallmentNumber
            null, // transferType
            null, // accountTransferDetails
            null, // noteText
            txnExternalId, // txnExternalId
            null, // loan
            null, // toSavingsAccount
            null, // fromSavingsAccount
            true, // isRegularTransaction
            false // isExceptionForBalanceCheck
            );

    log.info(
        "Executing internal transfer from account {} to account {} for amount {}",
        request.getFromAccountId(),
        request.getToAccountId(),
        request.getTransferAmount());

    // Execute the transfer using Fineract's native service
    // This bypasses the CommandSource logging and avoids the AppUser JPA issue
    Long transferTransactionId = accountTransfersWritePlatformService.transferFunds(transferDTO);

    log.info(
        "Internal transfer completed successfully with transaction ID: {}", transferTransactionId);

    // Return the result with the transaction ID
    return new CommandProcessingResultBuilder().withEntityId(transferTransactionId).build();
  }
}
