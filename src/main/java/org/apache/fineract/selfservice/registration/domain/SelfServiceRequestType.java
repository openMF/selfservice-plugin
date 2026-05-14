package org.apache.fineract.selfservice.registration.domain;

/**
 * Identifies the type of self-service request stored in {@code request_audit_table}.
 *
 * <p>The value determines which workflow may consume the stored token and ensures that a token
 * issued for one workflow cannot be reused in another.
 */
public enum SelfServiceRequestType {
  /**
   * Request created for self-service registration confirmation.
   *
   * <p>Use this for tokens that activate or finalize creation of a self-service user.
   */
  REGISTRATION,

  /**
   * Request created for self-enrollment confirmation.
   *
   * <p>Use this for tokens that activate a disabled user created during one-shot self-enrollment.
   */
  ENROLLMENT,

  /**
   * Request created for self-service password reset.
   *
   * <p>Use this for tokens that authorize renewal of an existing self-service password.
   */
  PASSWORD_RESET
}
