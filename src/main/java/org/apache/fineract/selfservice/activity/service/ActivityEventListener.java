/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.activity.service;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.selfservice.activity.domain.ActivityOutcome;
import org.apache.fineract.selfservice.activity.domain.ActivityType;
import org.apache.fineract.selfservice.notification.SelfServiceNotificationEvent;
import org.apache.fineract.selfservice.stepup.event.StepUpEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Turns the events the plugin already publishes (logins, transfers, beneficiaries, ...) into
 * activity history, without touching the places that publish them.
 *
 * <p>It is a listener of its own, so it does <b>not</b> depend on the notification settings: the
 * notification service skips an event when notifications are switched off, when the event type is
 * disabled (failed logins are by default) or during a cooldown, none of which may hide an action
 * from the audit history.
 *
 * <p>{@code AFTER_COMMIT}: an event published inside a transaction is only recorded once that
 * transaction committed, so a transfer that rolled back never shows up as confirmed. Events
 * published outside a transaction (a login) are recorded at once ({@code fallbackExecution}). It
 * runs on the publishing thread, so the tenant is already bound.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ActivityEventListener {

  /** What an event is recorded as. */
  record Mapped(ActivityType type, ActivityOutcome outcome, Map<String, Object> extra) {}

  private final ActivityLogService activityLogService;

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  public void onNotificationEvent(final SelfServiceNotificationEvent event) {
    boolean tenantBoundHere = false;
    try {
      final Optional<Mapped> mapped = map(event.getType());
      if (mapped.isEmpty() || event.getUserId() == null) {
        return;
      }
      tenantBoundHere = bindTenantIfMissing(event);
      final Map<String, Object> source = new HashMap<>(event.getContextData());
      source.putAll(mapped.get().extra());
      activityLogService.record(
          mapped.get().type(),
          mapped.get().outcome(),
          event.getUserId(),
          null,
          event.getIpAddress(),
          source);
    } catch (Exception e) {
      log.warn("Could not turn event {} into an activity: {}", event.getType(), e.getMessage());
    } finally {
      if (tenantBoundHere) {
        ThreadLocalContextUtil.clearTenant();
      }
    }
  }

  /**
   * The step-up module publishes its own audit events ({@code StepUpEvent}); three of them belong
   * in the history: a code was requested, a code was verified, the challenge got locked. Wrong
   * attempts, resends and expiries are left out on purpose, they would only flood the feed.
   */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  public void onStepUpEvent(final StepUpEvent event) {
    try {
      final Optional<Mapped> mapped = mapStepUp(event.type());
      if (mapped.isEmpty() || event.userId() == null) {
        return;
      }
      activityLogService.record(
          mapped.get().type(),
          mapped.get().outcome(),
          event.userId(),
          null,
          event.ipAddress(),
          event.action() == null ? Map.of() : Map.of("action", event.action().name()));
    } catch (Exception e) {
      log.warn(
          "Could not turn step-up event {} into an activity: {}", event.type(), e.getMessage());
    }
  }

  static Optional<Mapped> mapStepUp(final StepUpEvent.Type type) {
    if (type == null) {
      return Optional.empty();
    }
    return switch (type) {
      case ISSUED -> of(ActivityType.STEP_UP_REQUESTED, ActivityOutcome.SUCCESS);
      case VERIFIED -> of(ActivityType.STEP_UP_VERIFIED, ActivityOutcome.SUCCESS);
      case LOCKED -> of(ActivityType.STEP_UP_LOCKED, ActivityOutcome.FAILURE);
      default -> Optional.empty();
    };
  }

  /** The activity an event stands for, or empty for the events that are not part of the history. */
  static Optional<Mapped> map(final SelfServiceNotificationEvent.Type type) {
    if (type == null) {
      return Optional.empty();
    }
    return switch (type) {
      case LOGIN_SUCCESS -> of(ActivityType.LOGIN, ActivityOutcome.SUCCESS);
      case LOGIN_FAILURE -> of(ActivityType.LOGIN_FAILED, ActivityOutcome.FAILURE);
      case LOGIN_UNKNOWN_DEVICE -> of(ActivityType.LOGIN_NEW_DEVICE, ActivityOutcome.SUCCESS);
      case LOGOUT -> of(ActivityType.LOGOUT, ActivityOutcome.SUCCESS);
      case PASSWORD_RENEWED -> of(ActivityType.PASSWORD_RESET, ActivityOutcome.SUCCESS);
      case TRANSFER_SUCCESS -> of(ActivityType.TRANSFER_CONFIRMED, ActivityOutcome.SUCCESS);
      case BENEFICIARY_ADDED -> of(ActivityType.BENEFICIARY_ADDED, ActivityOutcome.SUCCESS);
      case BENEFICIARY_UPDATED -> of(ActivityType.BENEFICIARY_UPDATED, ActivityOutcome.SUCCESS);
      case BENEFICIARY_DELETED -> of(ActivityType.BENEFICIARY_DELETED, ActivityOutcome.SUCCESS);
      case SINPE_SUBSCRIPTION_CREATED -> sinpe("created");
      case SINPE_SUBSCRIPTION_UPDATED -> sinpe("updated");
      case SINPE_SUBSCRIPTION_DELETED -> sinpe("deleted");
      default -> Optional.empty();
    };
  }

  private static Optional<Mapped> of(final ActivityType type, final ActivityOutcome outcome) {
    return Optional.of(new Mapped(type, outcome, Map.of()));
  }

  private static Optional<Mapped> sinpe(final String action) {
    return Optional.of(
        new Mapped(
            ActivityType.SINPE_SUBSCRIPTION_CHANGED,
            ActivityOutcome.SUCCESS,
            Map.of("action", action)));
  }

  /** Normally the tenant is bound already; an event published on another thread carries its own. */
  private static boolean bindTenantIfMissing(final SelfServiceNotificationEvent event) {
    FineractPlatformTenant current = null;
    try {
      current = ThreadLocalContextUtil.getTenant();
    } catch (IllegalStateException ignored) {
      // no tenant on this thread
    }
    if (current == null && event.getTenant() != null) {
      ThreadLocalContextUtil.setTenant(event.getTenant());
      return true;
    }
    return false;
  }
}
