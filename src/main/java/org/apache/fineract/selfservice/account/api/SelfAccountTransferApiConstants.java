/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.account.api;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public final class SelfAccountTransferApiConstants {

  private SelfAccountTransferApiConstants() {}

  // Funds Transfer parameters
  public static final String toOfficeIdParamName = "toOfficeId";
  public static final String toClientIdParamName = "toClientId";
  public static final String toAccountTypeParamName = "toAccountType";
  public static final String toAccountIdParamName = "toAccountId";
  public static final String transferDateParamName = "transferDate";
  public static final String transferAmountParamName = "transferAmount";
  public static final String transferDescriptionParamName = "transferDescription";
  public static final String dateFormatParamName = "dateFormat";
  public static final String localeParamName = "locale";
  public static final String fromAccountIdParamName = "fromAccountId";
  public static final String fromAccountTypeParamName = "fromAccountType";
  public static final String fromClientIdParamName = "fromClientId";
  public static final String fromOfficeIdParamName = "fromOfficeId";

  public static final Set<String> CREATE_TRANSFER_REQUEST_DATA_PARAMETERS =
      Collections.unmodifiableSet(
          new HashSet<>(
              Arrays.asList(
                  toOfficeIdParamName,
                  toClientIdParamName,
                  toAccountTypeParamName,
                  toAccountIdParamName,
                  transferDateParamName,
                  transferAmountParamName,
                  transferDescriptionParamName,
                  dateFormatParamName,
                  localeParamName,
                  fromAccountIdParamName,
                  fromAccountTypeParamName,
                  fromClientIdParamName,
                  fromOfficeIdParamName)));
}
