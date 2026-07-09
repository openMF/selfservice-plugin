package org.apache.fineract.selfservice.account.service;

import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.selfservice.account.data.AccountTransferConfirmRequest;

public interface SelfAccountTransferWritePlatformService {
    CommandProcessingResult executeInternalTransfer(AccountTransferConfirmRequest request);
}