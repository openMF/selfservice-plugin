/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.security.domain;

import java.time.OffsetDateTime;
import lombok.Builder;
import lombok.Value;

/**
 * Immutable DTO for transferring audit event data between layers. Used by the API layer to pass
 * request context to the audit service without coupling the audit service to JAX-RS.
 */
@Value
@Builder
public class SelfServiceAccessAuditDto {

  Long appUserId;
  String username;
  SelfServiceAccessAudit.ResourceType resourceType;
  Long resourceId;
  String resourceIdentifier;
  SelfServiceAccessAudit.AccessResult accessResult;
  String endpoint;
  String httpMethod;
  String ipAddress;
  OffsetDateTime timestamp;
}
