/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.stepup.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.fineract.selfservice.stepup.domain.StepUpAction;
import org.junit.jupiter.api.Test;

class StepUpPayloadHasherTest {

  private static Map<String, String> tpt(
      final String fromId, final String toId, final String amount) {
    final Map<String, String> values = new HashMap<>();
    values.put("fromAccountId", fromId);
    values.put("fromAccountType", "2");
    values.put("toAccountId", toId);
    values.put("toAccountType", "2");
    values.put("transferAmount", amount);
    return values;
  }

  @Test
  void samePayload_givesSameHash_andIsSha256Hex() {
    final String a = StepUpPayloadHasher.hash(StepUpAction.TPT_TRANSFER, tpt("1", "2", "100"));
    final String b = StepUpPayloadHasher.hash(StepUpAction.TPT_TRANSFER, tpt("1", "2", "100"));
    assertEquals(a, b);
    assertEquals(64, a.length());
  }

  @Test
  void changingDestinationAccountChangesTheHash() {
    assertNotEquals(
        StepUpPayloadHasher.hash(StepUpAction.TPT_TRANSFER, tpt("1", "2", "100")),
        StepUpPayloadHasher.hash(StepUpAction.TPT_TRANSFER, tpt("1", "3", "100")));
  }

  @Test
  void changingSourceAccountChangesTheHash() {
    assertNotEquals(
        StepUpPayloadHasher.hash(StepUpAction.TPT_TRANSFER, tpt("1", "2", "100")),
        StepUpPayloadHasher.hash(StepUpAction.TPT_TRANSFER, tpt("9", "2", "100")));
  }

  @Test
  void changingAmountChangesTheHash() {
    assertNotEquals(
        StepUpPayloadHasher.hash(StepUpAction.TPT_TRANSFER, tpt("1", "2", "100")),
        StepUpPayloadHasher.hash(StepUpAction.TPT_TRANSFER, tpt("1", "2", "100.01")));
  }

  @Test
  void amountScaleAndFormattingDoNotChangeTheHash() {
    final String base = StepUpPayloadHasher.hash(StepUpAction.TPT_TRANSFER, tpt("1", "2", "100"));
    assertEquals(
        base, StepUpPayloadHasher.hash(StepUpAction.TPT_TRANSFER, tpt("1", "2", "100.00")));
    assertEquals(
        base, StepUpPayloadHasher.hash(StepUpAction.TPT_TRANSFER, tpt("1", "2", " 100.0 ")));
    assertEquals(
        StepUpPayloadHasher.hash(StepUpAction.TPT_TRANSFER, tpt("1", "2", "0")),
        StepUpPayloadHasher.hash(StepUpAction.TPT_TRANSFER, tpt("1", "2", "0.000")));
  }

  @Test
  void idNormalisationIgnoresDecimalZeroSuffix() {
    assertEquals(
        StepUpPayloadHasher.hash(StepUpAction.TPT_TRANSFER, tpt("1", "2", "5")),
        StepUpPayloadHasher.hash(StepUpAction.TPT_TRANSFER, tpt("1.0", "2", "5")));
  }

  @Test
  void sameFieldsButDifferentAction_giveDifferentHashes() {
    assertNotEquals(
        StepUpPayloadHasher.hash(StepUpAction.TPT_TRANSFER, tpt("1", "2", "100")),
        StepUpPayloadHasher.hash(StepUpAction.OWN_TRANSFER, tpt("1", "2", "100")));
  }

  @Test
  void changePassword_isBoundToTheActionOnly() {
    final String empty = StepUpPayloadHasher.hash(StepUpAction.CHANGE_PASSWORD, Map.of());
    final String withNoise =
        StepUpPayloadHasher.hash(StepUpAction.CHANGE_PASSWORD, Map.of("password", "secret"));
    assertEquals(empty, withNoise, "password content must never influence the hash");
    assertNotEquals(empty, StepUpPayloadHasher.hash(StepUpAction.TPT_TRANSFER, Map.of()));
  }

  @Test
  void confirmTransfer_normalisesWhitespaceAndCaseOfCodes() {
    final Map<String, String> a = new HashMap<>();
    a.put("fromAccount", "000000026");
    a.put("toAccount", "CR05 0152 0200 1026 2840 66");
    a.put("transferType", "same_bank");
    a.put("currencyCode", "crc");
    a.put("transferAmount", "10.50");
    final Map<String, String> b = new HashMap<>();
    b.put("fromAccount", " 000000026 ");
    b.put("toAccount", "CR05015202001026284066");
    b.put("transferType", "SAME_BANK");
    b.put("currencyCode", "CRC");
    b.put("transferAmount", "10.5");
    assertEquals(
        StepUpPayloadHasher.hash(StepUpAction.CONFIRM_TRANSFER, a),
        StepUpPayloadHasher.hash(StepUpAction.CONFIRM_TRANSFER, b));
  }

  @Test
  void delimiterInjection_cannotForgeAnotherFieldSplit() {
    // A text value that tries to smuggle a second field into the canonical string.
    final Map<String, String> honest = new HashMap<>();
    honest.put("fromAccount", "A");
    honest.put("toAccount", "B");
    honest.put("transferType", "SAME_BANK");
    honest.put("transferAmount", "1");

    final Map<String, String> forged = new HashMap<>();
    forged.put("fromAccount", "A|fromAccountType=|toAccount=B");
    forged.put("transferType", "SAME_BANK");
    forged.put("transferAmount", "1");

    assertNotEquals(
        StepUpPayloadHasher.hash(StepUpAction.CONFIRM_TRANSFER, honest),
        StepUpPayloadHasher.hash(StepUpAction.CONFIRM_TRANSFER, forged));
    assertTrue(
        StepUpPayloadHasher.canonicalize(StepUpAction.CONFIRM_TRANSFER, forged)
            .contains("\\|fromAccountType\\=\\|toAccount\\=B"));
  }

  @Test
  void missingRequiredFields_areReported() {
    final Map<String, String> values = new HashMap<>();
    values.put("fromAccountId", "1");
    values.put("transferAmount", " ");
    final List<String> missing =
        StepUpPayloadHasher.missingRequiredFields(StepUpAction.TPT_TRANSFER, values);
    assertEquals(
        List.of("fromAccountType", "toAccountId", "toAccountType", "transferAmount"), missing);
    assertTrue(
        StepUpPayloadHasher.missingRequiredFields(StepUpAction.CHANGE_PASSWORD, Map.of())
            .isEmpty());
  }

  @Test
  void valuesFromJson_readsPrimitivesAndKeepsNonPrimitivesAsJsonText() {
    final JsonObject json =
        JsonParser.parseString(
                "{\"fromAccountId\":12,\"fromAccountType\":2,\"toAccountId\":\"7\","
                    + "\"toAccountType\":{\"x\":1},\"transferAmount\":100.50,\"ignored\":\"y\"}")
            .getAsJsonObject();
    final Map<String, String> values =
        StepUpPayloadHasher.valuesFromJson(StepUpAction.TPT_TRANSFER, json);
    assertEquals("12", values.get("fromAccountId"));
    assertEquals("7", values.get("toAccountId"));
    assertEquals("100.50", values.get("transferAmount"));
    assertEquals("{\"x\":1}", values.get("toAccountType"));
    assertTrue(!values.containsKey("ignored"));
    // A non-primitive id can never hash like a valid numeric id.
    assertNotEquals(
        StepUpPayloadHasher.hash(StepUpAction.TPT_TRANSFER, values),
        StepUpPayloadHasher.hash(StepUpAction.TPT_TRANSFER, tpt("12", "7", "100.5")));
  }

  @Test
  void jsonAndMapRoute_produceTheSameHashForTheSamePayload() {
    final JsonObject json =
        JsonParser.parseString(
                "{\"fromAccountId\":1,\"fromAccountType\":2,\"toAccountId\":2,"
                    + "\"toAccountType\":2,\"transferAmount\":100}")
            .getAsJsonObject();
    assertEquals(
        StepUpPayloadHasher.hash(
            StepUpAction.TPT_TRANSFER,
            StepUpPayloadHasher.valuesFromJson(StepUpAction.TPT_TRANSFER, json)),
        StepUpPayloadHasher.hash(StepUpAction.TPT_TRANSFER, tpt("1", "2", "100")));
  }
}
