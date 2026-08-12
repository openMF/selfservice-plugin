package org.apache.fineract.selfservice.account.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.selfservice.account.data.PaymentDetailDao;
import org.apache.fineract.selfservice.account.data.PaymentDetailUpdateRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service layer to handle business logic regarding Payment Detail updates.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentDetailService {

    private final PaymentDetailDao paymentDetailDao;

    @Transactional
    public void updateRoutingCode(PaymentDetailUpdateRequest request) {
        log.warn("request.getPaymentDetailId() "+request.getPaymentDetailId());
        log.warn("request.getRoutingCode() "+request.getRoutingCode());
        if (request == null || request.getPaymentDetailId() == null || request.getRoutingCode() == null) {
            log.warn("Attempted to update routing code with invalid request");
            return;
        }
        
        paymentDetailDao.updateRoutingCode(request.getPaymentDetailId(), request.getRoutingCode());
    }
}