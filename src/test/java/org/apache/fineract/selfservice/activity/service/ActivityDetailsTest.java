/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.activity.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.HashMap;
import java.util.Map;
import org.apache.fineract.selfservice.activity.domain.ActivityType;
import org.junit.jupiter.api.Test;

class ActivityDetailsTest {

  /** Looks like the context data of a real transfer event: lots of personal data around. */
  private static Map<String, Object> transferContext() {
    final Map<String, Object> context = new HashMap<>();
    context.put("transactionAmount", "1250.50");
    context.put("currencyCode", "usd");
    context.put("toClientName", "Maria Lopez");
    context.put("toAccountNumber", "0001-2345-6789-0042");
    context.put("transferId", "98765");
    context.put("fromAccountNumber", "000111222333");
    context.put("email", "ana@example.org");
    context.put("mobileNumber", "+506 8888 7777");
    context.put("authCode", "483920");
    context.put("password", "secret");
    context.put("ipAddress", "203.0.113.9");
    context.put("transferDescription", "rent for October");
    return context;
  }

  private static JsonObject json(final String text) {
    return JsonParser.parseString(text).getAsJsonObject();
  }

  @Test
  void transfer_keepsOnlyTheWhitelistedFacts() {
    final String text = ActivityDetails.toJson(ActivityType.TRANSFER_CONFIRMED, transferContext());
    final JsonObject details = json(text);

    assertEquals("1250.50", details.get("amount").getAsString());
    assertEquals("USD", details.get("currency").getAsString());
    assertEquals("Maria Lopez", details.get("toName").getAsString());
    assertEquals("0042", details.get("toAccountLast4").getAsString());
    assertEquals("98765", details.get("transferId").getAsString());
    assertEquals(5, details.size());
  }

  @Test
  void personalDataAndSecretsOfTheSourceNeverReachTheDetails() {
    final String text = ActivityDetails.toJson(ActivityType.TRANSFER_CONFIRMED, transferContext());

    for (final String forbidden :
        new String[] {
          "ana@example.org",
          "8888",
          "483920",
          "secret",
          "203.0.113.9",
          "rent",
          "000111222333",
          "0001-2345-6789-0042",
          "6789"
        }) {
      assertFalse(text.contains(forbidden), "leaked: " + forbidden);
    }
  }

  @Test
  void aPhoneNumberAsDestination_isReducedToItsLastFourDigits() {
    final Map<String, Object> source = new HashMap<>();
    source.put("transactionAmount", "10");
    source.put("toAccountNumber", "+506 8888-7777");

    final JsonObject details =
        json(ActivityDetails.toJson(ActivityType.TRANSFER_CONFIRMED, source));

    assertEquals("7777", details.get("toAccountLast4").getAsString());
    assertFalse(details.toString().contains("8888"));
  }

  @Test
  void invalidValuesAreDroppedInsteadOfStored() {
    final Map<String, Object> source = new HashMap<>();
    source.put("transactionAmount", "ten; DROP TABLE x");
    source.put("currencyCode", "DOLLARS");
    source.put("toClientName", "N/A");
    source.put("toAccountNumber", "12");
    source.put("transferId", "abc");

    assertNull(ActivityDetails.toJson(ActivityType.TRANSFER_CONFIRMED, source));
  }

  @Test
  void aCommaAmountIsNormalised_andALongNameIsShortened() {
    final Map<String, Object> source = new HashMap<>();
    source.put("transactionAmount", "12,5");
    source.put("toClientName", "x".repeat(500) + "\n\t");

    final JsonObject details =
        json(ActivityDetails.toJson(ActivityType.TRANSFER_CONFIRMED, source));

    assertEquals("12.5", details.get("amount").getAsString());
    assertEquals(100, details.get("toName").getAsString().length());
  }

  @Test
  void currencyFallsBackToTheAlternativeKey() {
    final Map<String, Object> source = new HashMap<>();
    source.put("transactionAmount", "5");
    source.put("currency", "crc");

    assertEquals(
        "CRC",
        json(ActivityDetails.toJson(ActivityType.TRANSFER_CONFIRMED, source))
            .get("currency")
            .getAsString());
  }

  @Test
  void beneficiary_keepsNameAndId_only() {
    final Map<String, Object> source = new HashMap<>();
    source.put("beneficiaryName", "Rent account");
    source.put("beneficiaryId", 17L);
    source.put("accountNumber", "000111222333");
    source.put("email", "x@y.z");

    final JsonObject details = json(ActivityDetails.toJson(ActivityType.BENEFICIARY_ADDED, source));

    assertEquals("Rent account", details.get("name").getAsString());
    assertEquals("17", details.get("beneficiaryId").getAsString());
    assertEquals(2, details.size());
  }

  @Test
  void sinpe_acceptsOnlyTheThreeKnownActions() {
    assertEquals(
        "created",
        json(ActivityDetails.toJson(
                ActivityType.SINPE_SUBSCRIPTION_CHANGED, Map.of("action", "Created")))
            .get("action")
            .getAsString());
    assertNull(
        ActivityDetails.toJson(ActivityType.SINPE_SUBSCRIPTION_CHANGED, Map.of("action", "hack")));
  }

  @Test
  void stepUp_keepsTheProtectedActionCode() {
    assertEquals(
        "TPT_TRANSFER",
        json(ActivityDetails.toJson(
                ActivityType.STEP_UP_REQUESTED, Map.of("action", "TPT_TRANSFER")))
            .get("action")
            .getAsString());
    assertNull(ActivityDetails.toJson(ActivityType.STEP_UP_REQUESTED, Map.of("action", "tpt;--")));
  }

  @Test
  void typesWithoutDetails_neverStoreAnything() {
    for (final ActivityType type :
        new ActivityType[] {
          ActivityType.LOGIN,
          ActivityType.LOGIN_FAILED,
          ActivityType.LOGIN_NEW_DEVICE,
          ActivityType.LOGOUT,
          ActivityType.PASSWORD_CHANGED,
          ActivityType.PASSWORD_RESET,
          ActivityType.PROFILE_IMAGE_CHANGED,
          ActivityType.PROFILE_IMAGE_REMOVED
        }) {
      assertTrue(ActivityDetails.allowedKeys(type).isEmpty(), type.name());
      assertNull(ActivityDetails.toJson(type, transferContext()), type.name());
    }
  }

  @Test
  void aNullOrEmptySourceGivesNoDetails() {
    assertNull(ActivityDetails.toJson(ActivityType.TRANSFER_CONFIRMED, null));
    assertNull(ActivityDetails.toJson(ActivityType.TRANSFER_CONFIRMED, Map.of()));
  }

  @Test
  void everyActivityTypeIsHandledExplicitly() {
    // Guards against adding a type and forgetting to decide what its details may contain.
    for (final ActivityType type : ActivityType.values()) {
      ActivityDetails.allowedKeys(type); // must not throw
    }
  }
}
