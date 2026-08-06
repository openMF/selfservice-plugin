package org.apache.fineract.selfservice.security.service;

import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.infrastructure.core.util.TransactionDateUtil;
import org.apache.fineract.selfservice.security.domain.SelfServiceDeviceFingerprint;
import org.apache.fineract.selfservice.security.domain.SelfServiceDeviceFingerprintRepository;
import org.apache.fineract.selfservice.security.util.DeviceFingerprintUtil.DeviceSignals;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class SelfServiceDeviceFingerprintService {

  private final SelfServiceDeviceFingerprintRepository repository;
  private final TransactionDateUtil transactionDateUtil;

  /**
   * Registers a new device fingerprint or refreshes last-seen metadata for an existing one.
   *
   * <p>Failures are non-fatal: login must not break for legacy users or FK/data issues.
   *
   * @return persisted entity, or {@code null} if skipped / failed
   */
  @Transactional
  public SelfServiceDeviceFingerprint registerOrTouch(
      Long appUserId, DeviceSignals signals, boolean trustNewDevice) {
    if (appUserId == null || signals == null || StringUtils.isBlank(signals.fingerprintHash())) {
      return null;
    }

    try {
      LocalDateTime now = transactionDateUtil.getCurrentTenantLocalDateTime();

      return repository
          .findByAppUserIdAndFingerprintHash(appUserId, signals.fingerprintHash())
          .map(
              existing -> {
                existing.touch(now);
                existing.setUserAgent(signals.userAgent());
                existing.setIpAddress(signals.ipAddress());
                existing.setAcceptLanguage(signals.acceptLanguage());
                existing.setDeviceLabel(signals.deviceLabel());
                return repository.saveAndFlush(existing);
              })
          .orElseGet(
              () -> {
                SelfServiceDeviceFingerprint created =
                    SelfServiceDeviceFingerprint.builder()
                        .appUserId(appUserId)
                        .fingerprintHash(signals.fingerprintHash())
                        .userAgent(signals.userAgent())
                        .ipAddress(signals.ipAddress())
                        .acceptLanguage(signals.acceptLanguage())
                        .deviceLabel(signals.deviceLabel())
                        .firstSeenAt(now)
                        .lastSeenAt(now)
                        .trusted(trustNewDevice)
                        .build();
                log.info(
                    "Registered device fingerprint for userId={}, hashPrefix={}, trusted={}",
                    appUserId,
                    shortHash(signals.fingerprintHash()),
                    trustNewDevice);
                return repository.saveAndFlush(created);
              });
    } catch (DataIntegrityViolationException | JpaSystemException e) {
      // e.g. FK to m_appuser when self-service id is not present — never break login
      Throwable cause = e.getMostSpecificCause() != null ? e.getMostSpecificCause() : e;
      log.warn(
          "Could not persist device fingerprint for userId={} (non-fatal): {}",
          appUserId,
          cause.getMessage());
      return null;
    } catch (Exception e) {
      log.warn(
          "Could not persist device fingerprint for userId={} (non-fatal)",
          appUserId,
          e);
      return null;
    }
  }

  /**
   * @return {@code true} if this user already has at least one stored fingerprint (baseline exists)
   */
  @Transactional(readOnly = true)
  public boolean hasAnyDevice(Long appUserId) {
    if (appUserId == null) {
      return false;
    }
    try {
      return repository.countByAppUserId(appUserId) > 0;
    } catch (Exception e) {
      log.warn("hasAnyDevice failed for userId={} (non-fatal)", appUserId, e);
      return false;
    }
  }

  /**
   * @return {@code true} if the given hash is already registered for this user
   */
  @Transactional(readOnly = true)
  public boolean isKnownDevice(Long appUserId, String fingerprintHash) {
    if (appUserId == null || StringUtils.isBlank(fingerprintHash)) {
      return false;
    }
    try {
      return repository.existsByAppUserIdAndFingerprintHash(appUserId, fingerprintHash);
    } catch (Exception e) {
      log.warn("isKnownDevice failed for userId={} (non-fatal)", appUserId, e);
      return false;
    }
  }

  private static String shortHash(String hash) {
    if (hash == null || hash.length() < 12) {
      return hash != null ? hash : "n/a";
    }
    return hash.substring(0, 12);
  }
}