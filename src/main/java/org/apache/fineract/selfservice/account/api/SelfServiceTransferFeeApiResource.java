/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.account.api;

import com.google.gson.Gson;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.exchangerate.domain.BccrExchangeRate;
import org.apache.fineract.exchangerate.service.BccrExchangeRateService;
import org.apache.fineract.infrastructure.core.serialization.DefaultToApiJsonSerializer;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.selfservice.account.domain.SelfServiceTransferFee;
import org.apache.fineract.selfservice.account.domain.SelfServiceTransferFeeRepository;
import org.apache.fineract.selfservice.security.service.PlatformSelfServiceSecurityContext;
import org.springframework.stereotype.Component;

@Component
@Path("/v1/self/transfer-fees")
@Tag(
    name = "Self Transfer Fees Management",
    description = "Endpoints to manage transfer fee configurations")
@RequiredArgsConstructor
public class SelfServiceTransferFeeApiResource {

  private final PlatformSelfServiceSecurityContext context;
  private final PlatformSecurityContext contextBackOffice;
  private final SelfServiceTransferFeeRepository feeRepository;
  private final DefaultToApiJsonSerializer<SelfServiceTransferFee> toApiJsonSerializer;
  private final BccrExchangeRateService bccrExchangeRateService;
  private final Gson gson = new Gson();

  @GET
  @Produces(MediaType.APPLICATION_JSON)
  @Operation(
      summary = "Get All Transfer Fees",
      description =
          "Retrieves all configured transfer fees. Includes the latest BCCR exchange rate only "
              + "for fees explicitly flagged as requiring exchange rate data.")
  public String getAll() {
    context.authenticatedSelfServiceUser();

    List<SelfServiceTransferFee> fees = feeRepository.findAll();

    // Fetch the latest BCCR sell rate once for all fees
    Optional<BccrExchangeRate> latestRateOpt = bccrExchangeRateService.getLatestRate();
    BigDecimal currentBccrRate = latestRateOpt.map(BccrExchangeRate::getSellRate).orElse(null);

    List<Map<String, Object>> responseList =
        fees.stream()
            .map(
                fee -> {
                  Map<String, Object> feeMap = new HashMap<>();
                  feeMap.put("id", fee.getId());
                  feeMap.put("transferType", fee.getTransferType());
                  feeMap.put("currencyCode", fee.getCurrencyCode());
                  feeMap.put("transferMode", fee.getTransferMode());
                  feeMap.put("feeType", fee.getFeeType());
                  feeMap.put("feeValue", fee.getFeeValue());
                  feeMap.put("feeCurrency", fee.getFeeCurrency());
                  feeMap.put("thresholdAmount", fee.getThresholdAmount());
                  feeMap.put("thresholdFeeValue", fee.getThresholdFeeValue());
                  feeMap.put("description", fee.getDescription());
                  feeMap.put("isActive", fee.isActive());
                  feeMap.put("exchangeRateRequired", fee.isExchangeRateRequired());

                  // Only include BCCR rate when explicitly required by configuration
                  if (fee.isExchangeRateRequired()) {
                    feeMap.put("currentBccrRate", currentBccrRate);
                  }

                  return feeMap;
                })
            .collect(Collectors.toList());

    return gson.toJson(responseList);
  }
}
