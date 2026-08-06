/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.onboarding.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.util.TransactionDateUtil;
import org.apache.fineract.onboarding.domain.OnboardingProgressData;
import org.apache.fineract.onboarding.domain.OnboardingStepData;
import org.apache.fineract.onboarding.domain.OnboardingStepStatus;
import static org.apache.fineract.onboarding.domain.OnboardingStepStatus.IN_PROGRESS;
import static org.apache.fineract.onboarding.domain.OnboardingStepStatus.PENDING;
import org.apache.fineract.onboarding.domain.SelfServiceOnboardingStep;
import org.apache.fineract.onboarding.domain.SelfServiceOnboardingStepDef;
import org.apache.fineract.onboarding.domain.SelfServiceOnboardingStepDefRepository;
import org.apache.fineract.onboarding.domain.SelfServiceOnboardingStepRepository;
import org.apache.fineract.onboarding.domain.UpdateOnboardingStepRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class SelfServiceOnboardingStepService {

  private final SelfServiceOnboardingStepDefRepository stepDefRepository;
  private final SelfServiceOnboardingStepRepository stepRepository;
  private final TransactionDateUtil transactionDateUtil;

  /** Creates progress rows for every active step definition (idempotent). */
  @Transactional
  public void initializeSteps(Long appUserId) {
    if (appUserId == null || stepRepository.existsByAppUserId(appUserId)) {
      return;
    }
    List<SelfServiceOnboardingStepDef> defs =
        stepDefRepository.findByActiveTrueOrderByStepOrderAsc();
    if (defs.isEmpty()) {
      log.warn("No active onboarding step definitions found — skip init for userId={}", appUserId);
      return;
    }
    LocalDateTime now = transactionDateUtil.getCurrentTenantLocalDateTime();
    List<SelfServiceOnboardingStep> rows = new ArrayList<>();
    for (SelfServiceOnboardingStepDef def : defs) {
      rows.add(
          SelfServiceOnboardingStep.builder()
              .appUserId(appUserId)
              .stepDef(def)
              .status(OnboardingStepStatus.PENDING)
              .createdAt(now)
              .updatedAt(now)
              .build());
    }
    stepRepository.saveAll(rows);
    log.info(
        "Onboarding steps initialized for userId={}, count={}", appUserId, rows.size());
  }

  /**
   * Marks all active steps with step_order &lt;= target order as COMPLETED.
   *
   * @param upToStepCode e.g. {@code PASSWORD_SETUP} from DB seed
   */
  @Transactional
  public void completeStepsUpTo(Long appUserId, String upToStepCode) {
    if (appUserId == null || StringUtils.isBlank(upToStepCode)) {
      return;
    }
    initializeSteps(appUserId);
    SelfServiceOnboardingStepDef target =
        stepDefRepository
            .findByStepCode(upToStepCode.trim().toUpperCase())
            .orElse(null);
    if (target == null) {
      log.warn("completeStepsUpTo: unknown stepCode={}", upToStepCode);
      return;
    }
    LocalDateTime now = transactionDateUtil.getCurrentTenantLocalDateTime();
    List<SelfServiceOnboardingStep> steps = stepRepository.findByAppUserIdOrdered(appUserId);
    for (SelfServiceOnboardingStep step : steps) {
      if (step.getStepDef().getStepOrder() <= target.getStepOrder()
          && step.getStatus() != OnboardingStepStatus.COMPLETED) {
        step.markCompleted(now, null);
      }
    }
    stepRepository.saveAll(steps);
  }

  @Transactional
  public OnboardingProgressData updateStep(Long appUserId, UpdateOnboardingStepRequest request) {
    if (appUserId == null || request == null || StringUtils.isBlank(request.getStepCode())) {
      throw validationError("stepCode", "stepCode is required");
    }
    initializeSteps(appUserId);

    String code = request.getStepCode().trim().toUpperCase();
    SelfServiceOnboardingStepDef def =
        stepDefRepository
            .findByStepCodeAndActiveTrue(code)
            .orElseThrow(() -> validationError("stepCode", "Unknown or inactive stepCode: " + code));

    OnboardingStepStatus newStatus;
    try {
      newStatus =
          OnboardingStepStatus.valueOf(
              StringUtils.defaultIfBlank(request.getStatus(), "COMPLETED").toUpperCase());
    } catch (Exception e) {
      throw validationError("status", "Invalid status: " + request.getStatus());
    }

    SelfServiceOnboardingStep step =
        stepRepository
            .findByAppUserIdAndStepCode(appUserId, def.getStepCode())
            .orElseThrow(() -> validationError("stepCode", "Step not found for user"));

    LocalDateTime now = transactionDateUtil.getCurrentTenantLocalDateTime();
    switch (newStatus) {
      case IN_PROGRESS -> step.markInProgress(now);
      case COMPLETED -> step.markCompleted(now, request.getMetadataJson());
      case FAILED -> step.markFailed(now, request.getFailureReason());
      case SKIPPED -> step.markSkipped(now);
      case PENDING -> {
        step.setStatus(OnboardingStepStatus.PENDING);
        step.setUpdatedAt(now);
      }
      default -> {}
    }
    stepRepository.saveAndFlush(step);
    return getProgress(appUserId);
  }

  @Transactional(readOnly = true)
  public OnboardingProgressData getProgress(Long appUserId) {
    if (appUserId == null) {
      return emptyProgress();
    }
    List<SelfServiceOnboardingStep> steps = stepRepository.findByAppUserIdOrdered(appUserId);
    if (steps.isEmpty()) {
      return templateFromDefinitions();
    }
    return buildProgress(steps);
  }

  @Transactional
  public OnboardingProgressData getOrInitProgress(Long appUserId) {
    if (appUserId == null) {
      return emptyProgress();
    }
    if (!stepRepository.existsByAppUserId(appUserId)) {
      initializeSteps(appUserId);
    }
    // Sync: if new defs were added after user init, create missing PENDING rows
    syncMissingSteps(appUserId);
    return getProgress(appUserId);
  }

  /** Adds progress rows for any new active definitions not yet assigned to the user. */
  @Transactional
  public void syncMissingSteps(Long appUserId) {
    List<SelfServiceOnboardingStepDef> defs =
        stepDefRepository.findByActiveTrueOrderByStepOrderAsc();
    List<SelfServiceOnboardingStep> existing = stepRepository.findByAppUserIdOrdered(appUserId);
    Set<Long> have =
        existing.stream().map(s -> s.getStepDef().getId()).collect(Collectors.toSet());
    LocalDateTime now = transactionDateUtil.getCurrentTenantLocalDateTime();
    List<SelfServiceOnboardingStep> toAdd = new ArrayList<>();
    for (SelfServiceOnboardingStepDef def : defs) {
      if (!have.contains(def.getId())) {
        toAdd.add(
            SelfServiceOnboardingStep.builder()
                .appUserId(appUserId)
                .stepDef(def)
                .status(OnboardingStepStatus.PENDING)
                .createdAt(now)
                .updatedAt(now)
                .build());
      }
    }
    if (!toAdd.isEmpty()) {
      stepRepository.saveAll(toAdd);
      log.info("Synced {} new onboarding steps for userId={}", toAdd.size(), appUserId);
    }
  }

  private OnboardingProgressData buildProgress(List<SelfServiceOnboardingStep> steps) {
    int total = steps.size();
    int completed =
        (int)
            steps.stream()
                .filter(
                    s ->
                        s.getStatus() == OnboardingStepStatus.COMPLETED
                            || s.getStatus() == OnboardingStepStatus.SKIPPED)
                .count();
    int percent = total == 0 ? 0 : (int) Math.round((completed * 100.0) / total);
    boolean complete =
        total > 0
            && steps.stream()
                .filter(s -> s.getStepDef().isRequired())
                .allMatch(
                    s ->
                        s.getStatus() == OnboardingStepStatus.COMPLETED
                            || s.getStatus() == OnboardingStepStatus.SKIPPED);

    OnboardingStepData current =
        steps.stream()
            .filter(
                s ->
                    s.getStatus() == OnboardingStepStatus.IN_PROGRESS
                        || s.getStatus() == OnboardingStepStatus.PENDING
                        || s.getStatus() == OnboardingStepStatus.FAILED)
            .min(Comparator.comparingInt(s -> s.getStepDef().getStepOrder()))
            .map(this::toData)
            .orElse(null);

    return OnboardingProgressData.builder()
        .onboardingComplete(complete)
        .totalSteps(total)
        .completedSteps(completed)
        .progressPercent(percent)
        .currentStep(current)
        .steps(steps.stream().map(this::toData).toList())
        .build();
  }

  private OnboardingProgressData templateFromDefinitions() {
    List<SelfServiceOnboardingStepDef> defs =
        stepDefRepository.findByActiveTrueOrderByStepOrderAsc();
    List<OnboardingStepData> steps =
        defs.stream()
            .map(
                d ->
                    OnboardingStepData.builder()
                        .code(d.getStepCode())
                        .order(d.getStepOrder())
                        .label(d.getLabel())
                        .groupCode(d.getGroupCode())
                        .required(d.isRequired())
                        .status(OnboardingStepStatus.PENDING.name())
                        .build())
            .toList();
    return OnboardingProgressData.builder()
        .onboardingComplete(false)
        .totalSteps(steps.size())
        .completedSteps(0)
        .progressPercent(0)
        .currentStep(steps.isEmpty() ? null : steps.get(0))
        .steps(steps)
        .build();
  }

  private OnboardingStepData toData(SelfServiceOnboardingStep s) {
    SelfServiceOnboardingStepDef d = s.getStepDef();
    return OnboardingStepData.builder()
        .code(d.getStepCode())
        .order(d.getStepOrder())
        .label(d.getLabel())
        .groupCode(d.getGroupCode())
        .required(d.isRequired())
        .status(s.getStatus().name())
        .startedAt(s.getStartedAt() != null ? s.getStartedAt().toString() : null)
        .completedAt(s.getCompletedAt() != null ? s.getCompletedAt().toString() : null)
        .failureReason(s.getFailureReason())
        .metadataJson(s.getMetadataJson())
        .build();
  }

  private OnboardingProgressData emptyProgress() {
    return OnboardingProgressData.builder()
        .onboardingComplete(false)
        .totalSteps(0)
        .completedSteps(0)
        .progressPercent(0)
        .currentStep(null)
        .steps(List.of())
        .build();
  }

  private PlatformApiDataValidationException validationError(String param, String message) {
    List<ApiParameterError> errors = new ArrayList<>();
    errors.add(
        ApiParameterError.parameterError(
            "error.msg.self.onboarding.step.invalid", message, param, param));
    return new PlatformApiDataValidationException(errors);
  }
}