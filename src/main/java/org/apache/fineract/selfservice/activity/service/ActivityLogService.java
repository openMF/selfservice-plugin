/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.activity.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.core.util.TransactionDateUtil;
import org.apache.fineract.selfservice.activity.domain.ActivityOutcome;
import org.apache.fineract.selfservice.activity.domain.ActivityType;
import org.apache.fineract.selfservice.activity.domain.SelfServiceActivity;
import org.apache.fineract.selfservice.activity.domain.SelfServiceActivityRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Writes the activity history of a self-service user (US-E12-05).
 *
 * <p>Recording is <b>best effort and never breaks the request</b> that triggered it: every
 * exception is caught and logged (the same rule as the access audit). The row is written in its own
 * transaction, so it does not depend on, and cannot roll back, the caller's transaction.
 *
 * <p>What is stored is decided here, not by the caller: the IP address is the one of the connection
 * (never a header) and is masked, {@code details} comes from a per-type whitelist ({@link
 * ActivityDetails}), and a tenant that switched the log off gets nothing written.
 */
@Service
@Slf4j
public class ActivityLogService {

  /** At most one failed-login row per user and this period, so the feed cannot be flooded. */
  static final Duration FAILED_LOGIN_THROTTLE = Duration.ofMinutes(1);

  private final SelfServiceActivityRepository repository;
  private final ActivitySettingsProvider settingsProvider;
  private final TransactionDateUtil transactionDateUtil;
  private final TransactionTemplate newTransaction;
  private final Supplier<String> clientIp;

  @Autowired
  public ActivityLogService(
      final SelfServiceActivityRepository repository,
      final ActivitySettingsProvider settingsProvider,
      final TransactionDateUtil transactionDateUtil,
      final TransactionTemplate transactionTemplate) {
    this(
        repository,
        settingsProvider,
        transactionDateUtil,
        transactionTemplate,
        ActivityLogService::connectionAddress);
  }

  /** The address source is a parameter so that tests can say where the request came from. */
  ActivityLogService(
      final SelfServiceActivityRepository repository,
      final ActivitySettingsProvider settingsProvider,
      final TransactionDateUtil transactionDateUtil,
      final TransactionTemplate transactionTemplate,
      final Supplier<String> clientIp) {
    this.clientIp = clientIp;
    this.repository = repository;
    this.settingsProvider = settingsProvider;
    this.transactionDateUtil = transactionDateUtil;
    this.newTransaction = new TransactionTemplate(transactionTemplate.getTransactionManager());
    this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  /**
   * Records one activity.
   *
   * @param clientId the client the user acted for, or {@code null} when not known
   * @param source raw facts about the action (for example an event's context data); only the
   *     whitelisted keys of {@code type} are read
   */
  public void record(
      final ActivityType type,
      final ActivityOutcome outcome,
      final Long userId,
      final Long clientId,
      final Map<String, Object> source) {
    if (type == null || outcome == null || userId == null) {
      return;
    }
    try {
      if (!settingsProvider.current().enabled()) {
        return;
      }
      final LocalDateTime now = transactionDateUtil.getCurrentTenantLocalDateTime();
      newTransaction.executeWithoutResult(
          status -> {
            if (type == ActivityType.LOGIN_FAILED
                && repository.countSince(userId, type, now.minus(FAILED_LOGIN_THROTTLE)) > 0) {
              return;
            }
            repository.save(
                new SelfServiceActivity(
                    userId,
                    clientId,
                    type,
                    outcome,
                    IpAddressMasker.mask(clientIp.get()),
                    ActivityDetails.toJson(type, source),
                    now));
          });
    } catch (Exception e) {
      // Never let a failure of the history break the action it describes.
      log.warn("Could not record activity {} for user {}: {}", type, userId, e.getMessage());
    }
  }

  /**
   * The address of the connection of the current request, or {@code null} outside a request.
   *
   * <p>Never read from {@code X-Forwarded-For}: a client can write anything in that header, and the
   * history exists to show where an action really came from. Behind a reverse proxy the operator
   * configures the trusted proxies of the server (for example {@code
   * server.forward-headers-strategy=native}), and then {@code getRemoteAddr()} already is the real
   * client address.
   */
  static String connectionAddress() {
    try {
      if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
        return attrs.getRequest().getRemoteAddr();
      }
    } catch (RuntimeException e) {
      log.debug("No request available to read the IP address from: {}", e.getMessage());
    }
    return null;
  }
}
