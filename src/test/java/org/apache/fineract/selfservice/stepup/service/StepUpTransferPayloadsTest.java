/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.stepup.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.math.BigDecimal;
import org.apache.fineract.selfservice.stepup.domain.StepUpAction;
import org.junit.jupiter.api.Test;

/**
 * The hash computed from the JSON a client sends when asking for a challenge must equal the hash
 * computed from the validated request at the protected endpoint, for the same operation.
 */
class StepUpTransferPayloadsTest {

  @Test
  void tptTransfer_challengeJsonAndValidatedRequest_haveTheSameHash() {
    final JsonObject challengePayload =
        JsonParser.parseString(
                "{\"fromAccountId\":1,\"fromAccountType\":2,\"toAccountId\":2,"
                    + "\"toAccountType\":2,\"transferAmount\":100.00,\"transferDate\":\"x\"}")
            .getAsJsonObject();
    final String atChallenge =
        StepUpPayloadHasher.hash(
            StepUpAction.TPT_TRANSFER,
            StepUpPayloadHasher.valuesFromJson(StepUpAction.TPT_TRANSFER, challengePayload));

    final String atEnforcement =
        StepUpPayloadHasher.hash(
            StepUpAction.TPT_TRANSFER,
            StepUpTransferPayloads.forTransfer(1L, 2, 2L, 2, new BigDecimal("100")));

    assertEquals(atChallenge, atEnforcement);
  }

  @Test
  void tptTransfer_aDifferentAmountOrBeneficiaryAtEnforcement_doesNotMatch() {
    final String approved =
        StepUpPayloadHasher.hash(
            StepUpAction.TPT_TRANSFER,
            StepUpTransferPayloads.forTransfer(1L, 2, 2L, 2, new BigDecimal("100")));

    assertNotEquals(
        approved,
        StepUpPayloadHasher.hash(
            StepUpAction.TPT_TRANSFER,
            StepUpTransferPayloads.forTransfer(1L, 2, 2L, 2, new BigDecimal("100.01"))));
    assertNotEquals(
        approved,
        StepUpPayloadHasher.hash(
            StepUpAction.TPT_TRANSFER,
            StepUpTransferPayloads.forTransfer(1L, 2, 99L, 2, new BigDecimal("100"))));
  }

  @Test
  void confirmTransfer_challengeJsonAndConfirmRequest_haveTheSameHash() {
    final JsonObject challengePayload =
        JsonParser.parseString(
                "{\"fromAccount\":\"000000026\",\"fromAccountType\":2,"
                    + "\"toAccount\":\"CR05 0152 0200 1026 2840 66\",\"toAccountType\":2,"
                    + "\"transferType\":\"same_bank\",\"currencyCode\":\"crc\","
                    + "\"transferAmount\":\"10.50\",\"feeAmount\":1}")
            .getAsJsonObject();
    final String atChallenge =
        StepUpPayloadHasher.hash(
            StepUpAction.CONFIRM_TRANSFER,
            StepUpPayloadHasher.valuesFromJson(StepUpAction.CONFIRM_TRANSFER, challengePayload));

    final String atEnforcement =
        StepUpPayloadHasher.hash(
            StepUpAction.CONFIRM_TRANSFER,
            StepUpTransferPayloads.forConfirm(
                "000000026",
                2,
                "CR05015202001026284066",
                2,
                null,
                "SAME_BANK",
                "CRC",
                new BigDecimal("10.5")));

    assertEquals(atChallenge, atEnforcement);
  }

  @Test
  void confirmTransfer_aChangedDestinationOrAmount_doesNotMatch() {
    final String approved =
        StepUpPayloadHasher.hash(
            StepUpAction.CONFIRM_TRANSFER,
            StepUpTransferPayloads.forConfirm(
                "1", 2, "DEST-A", 2, null, "SAME_BANK", "CRC", new BigDecimal("10")));

    assertNotEquals(
        approved,
        StepUpPayloadHasher.hash(
            StepUpAction.CONFIRM_TRANSFER,
            StepUpTransferPayloads.forConfirm(
                "1", 2, "DEST-B", 2, null, "SAME_BANK", "CRC", new BigDecimal("10"))));
    assertNotEquals(
        approved,
        StepUpPayloadHasher.hash(
            StepUpAction.CONFIRM_TRANSFER,
            StepUpTransferPayloads.forConfirm(
                "1", 2, "DEST-A", 2, null, "SAME_BANK", "CRC", new BigDecimal("1000"))));
  }

  @Test
  void ownAndThirdPartyTransfers_neverShareADigest() {
    final String tpt =
        StepUpPayloadHasher.hash(
            StepUpAction.TPT_TRANSFER,
            StepUpTransferPayloads.forTransfer(1L, 2, 2L, 2, new BigDecimal("5")));
    final String own =
        StepUpPayloadHasher.hash(
            StepUpAction.OWN_TRANSFER,
            StepUpTransferPayloads.forTransfer(1L, 2, 2L, 2, new BigDecimal("5")));
    assertNotEquals(tpt, own);
  }
}
