package org.apache.fineract.infrastructure.core.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import org.apache.fineract.infrastructure.core.util.TransactionDateUtil;
import org.apache.fineract.selfservice.api.data.TransactionDateRequest;

@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionDateManagementService {

    private final TransactionDateUtil transactionDateUtil;

    /**
     * Processes and validates the transaction date for any incoming API request.
     * Ensures multi-tenant compliance by returning a timezone-safe OffsetDateTime.
     */
    public OffsetDateTime processAndValidateTransactionDate(TransactionDateRequest request) {
        if (request == null) {
            log.debug("Null transaction date request, defaulting to current tenant time");
            request = new TransactionDateRequest(null, null, null);
        }
        
        String fineractFormattedDate = transactionDateUtil.formatTransactionDateForFineract(
                request.getTransactionDate(), 
                request.getDateFormat(), 
                request.getLocale()
        );
        
        log.debug("Processed transaction date for Fineract: {}", fineractFormattedDate);
        return transactionDateUtil.parseFineractDate(fineractFormattedDate);
    }
}