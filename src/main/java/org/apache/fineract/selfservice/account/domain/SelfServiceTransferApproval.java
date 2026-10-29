/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.account.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A confirmed ENTITY-client transfer held until a second signatory approves it (MX-489).
 *
 * <p>{@code requestJson} is the initiator's confirm request without the OTP; approval replays it
 * through the normal confirm execution path.
 */
@Entity
@Table(name = "m_selfservice_transfer_approval")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SelfServiceTransferApproval {

  /** Lifecycle of a held transfer. Only PENDING can be decided. */
  public enum Status {
    /** Held, waiting for a second signatory. */
    PENDING,
    /** Approved by a second signatory; the transfer was executed. */
    APPROVED,
    /** Closed by a signatory; no money moved. */
    REJECTED,
    /** Approved, but execution failed. Final: reconcile, then the initiator submits again. */
    FAILED
  }

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "client_id", nullable = false)
  private Long clientId;

  @Column(name = "requested_by", nullable = false)
  private Long requestedBy;

  @Column(name = "decided_by")
  private Long decidedBy;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private Status status;

  @Column(name = "transfer_amount", nullable = false, precision = 19, scale = 6)
  private BigDecimal transferAmount;

  @Column(name = "currency_code", nullable = false, length = 3)
  private String currencyCode;

  @Column(name = "request_json", nullable = false, columnDefinition = "TEXT")
  private String requestJson;

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;

  @Column(name = "decided_at")
  private LocalDateTime decidedAt;
}
