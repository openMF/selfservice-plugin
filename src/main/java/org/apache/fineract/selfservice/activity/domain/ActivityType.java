/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.activity.domain;

/**
 * What a self-service user did (or tried to do). Only the code is stored: clients translate it, so
 * no localized text lives in the database.
 */
public enum ActivityType {
  LOGIN,
  LOGIN_FAILED,
  LOGIN_NEW_DEVICE,
  LOGOUT,
  PASSWORD_CHANGED,
  PASSWORD_RESET,
  TRANSFER_CONFIRMED,
  BENEFICIARY_ADDED,
  BENEFICIARY_UPDATED,
  BENEFICIARY_DELETED,
  PROFILE_IMAGE_CHANGED,
  PROFILE_IMAGE_REMOVED,
  SINPE_SUBSCRIPTION_CHANGED,
  STEP_UP_REQUESTED,
  STEP_UP_VERIFIED,
  STEP_UP_LOCKED
}
