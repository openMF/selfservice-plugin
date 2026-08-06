package org.apache.fineract.selfservice.security.service;

import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.core.util.TransactionDateUtil;
import org.apache.fineract.selfservice.security.domain.SelfServiceDeviceFingerprint;
import org.apache.fineract.selfservice.security.domain.SelfServiceDeviceFingerprintRepository;
import org.apache.fineract.selfservice.security.util.DeviceFingerprintUtil.DeviceSignals;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class SelfServiceDeviceFingerprintService {

  private final SelfServiceDeviceFingerprintRepository repository;
  private final TransactionDateUtil transactionDateUtil;

  @Transactional
  public SelfServiceDeviceFingerprint registerOrTouch(
      Long appUserId, DeviceSignals signals, boolean trustNewDevice) {
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
                  signals.fingerprintHash().substring(0, 12),
                  trustNewDevice);
              return repository.saveAndFlush(created);
            });
  }

  @Transactional(readOnly = true)
  public boolean isKnownDevice(Long appUserId, String fingerprintHash) {
    return repository.existsByAppUserIdAndFingerprintHash(appUserId, fingerprintHash);
  }
}