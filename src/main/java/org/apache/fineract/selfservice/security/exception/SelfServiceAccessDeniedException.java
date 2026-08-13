/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.security.exception;

import org.apache.fineract.infrastructure.core.exception.AbstractPlatformDomainRuleException;

/**
 * Unified exception thrown when a self-service user attempts to access a resource that is not
 * linked to their client profile.
 *
 * <p>SECURITY: This exception intentionally does NOT reveal whether the target resource exists or
 * its type. The error message is always identical regardless of whether the resource is a client,
 * savings account, loan, share account, or transfer source. This prevents account enumeration
 * attacks (IDOR probing).
 *
 * <p>Multi-tenant safe: inherits tenant context from Fineract's exception hierarchy.
 */
public class SelfServiceAccessDeniedException extends AbstractPlatformDomainRuleException {

  private static final String ERROR_CODE = "error.msg.selfservice.access.denied";
  private static final String DEFAULT_MESSAGE =
      "Access denied: the requested resource is not associated with your profile.";

  private final Long appUserId;
  private final String resourceType;
  private final Long resourceId;

  /**
   * Constructs an access denied exception for a numeric resource ID.
   *
   * @param appUserId the ID of the self-service user who was denied access
   * @param resourceType the type of resource attempted to access
   * @param resourceId the numeric ID of the requested resource
   */
  public SelfServiceAccessDeniedException(
      final Long appUserId, final String resourceType, final Long resourceId) {
    super(ERROR_CODE, DEFAULT_MESSAGE, "accessDenied");
    this.appUserId = appUserId;
    this.resourceType = resourceType;
    this.resourceId = resourceId;
  }

  /**
   * Constructs an access denied exception for a string resource identifier.
   * Intentionally sets the numeric resourceId to null.
   *
   * @param appUserId the ID of the self-service user who was denied access
   * @param resourceType the type of resource attempted to access
   * @param resourceIdentifier the string identifier of the requested resource
   */
  public SelfServiceAccessDeniedException(
      final Long appUserId, final String resourceType, final String resourceIdentifier) {
    super(ERROR_CODE, DEFAULT_MESSAGE, "accessDenied");
    this.appUserId = appUserId;
    this.resourceType = resourceType;
    this.resourceId = null;
  }

  /**
   * @return the ID of the self-service user who was denied access
   */
  public Long getAppUserId() {
    return appUserId;
  }

  /**
   * @return the type of resource the user attempted to access
   */
  public String getResourceType() {
    return resourceType;
  }

  /**
   * @return the numeric ID of the requested resource, or null if a string identifier was used
   */
  public Long getResourceId() {
    return resourceId;
  }
}
