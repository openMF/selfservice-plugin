package org.apache.fineract.selfservice.security.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;


@Entity
@Table(name = "m_selfservice_device_fingerprint")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SelfServiceDeviceFingerprint {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "appuser_id", nullable = false)
  private Long appUserId;

  @Column(name = "fingerprint_hash", nullable = false, length = 128)
  private String fingerprintHash;

  @Column(name = "user_agent", length = 512)
  private String userAgent;

  @Column(name = "ip_address", length = 64)
  private String ipAddress;

  @Column(name = "accept_language", length = 128)
  private String acceptLanguage;

  @Column(name = "device_label", length = 255)
  private String deviceLabel;

  @Column(name = "first_seen_at", nullable = false)
  private LocalDateTime firstSeenAt;

  @Column(name = "last_seen_at", nullable = false)
  private LocalDateTime lastSeenAt;

  @Column(name = "trusted", nullable = false)
  private boolean trusted = true;

  public void touch(LocalDateTime now) {
    this.lastSeenAt = now;
  }
}