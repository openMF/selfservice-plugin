/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.registration.domain;

import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface SelfServiceRegistrationRepository
    extends JpaRepository<SelfServiceRegistration, Long>,
        JpaSpecificationExecutor<SelfServiceRegistration> {

  String FIND_BY_REQUEST_AND_AUTHENTICATION_TOKEN =
      "select request from SelfServiceRegistration request where request.id = :id and "
          + "request.authenticationToken = :authenticationToken";

  String FIND_BY_REQUEST_TYPE_AND_AUTHENTICATION_TOKEN =
      "select request from SelfServiceRegistration request where request.id = :id and "
          + "request.authenticationToken = :authenticationToken and request.requestType = :requestType";

  String FIND_BY_EXTERNAL_AUTHORIZATION_TOKEN =
      "select request from SelfServiceRegistration request where request.externalAuthorizationToken = :externalAuthorizationToken and "
          + "request.requestType = :requestType";

  /**
   * Finds a self-service request by its identifier and authentication token.
   *
   * @param id request identifier
   * @param authenticationToken authentication token stored with the request
   * @return matching request, or {@code null} when no record matches
   */
  @Query(FIND_BY_REQUEST_AND_AUTHENTICATION_TOKEN)
  SelfServiceRegistration getRequestByIdAndAuthenticationToken(
      @Param("id") Long id, @Param("authenticationToken") String authenticationToken);

  /**
   * Finds a self-service request by identifier, authentication token, and request type.
   *
   * @param id request identifier
   * @param authenticationToken authentication token stored with the request
   * @param requestType expected request type
   * @return matching request, or {@code null} when no record matches
   */
  @Query(FIND_BY_REQUEST_TYPE_AND_AUTHENTICATION_TOKEN)
  SelfServiceRegistration getRequestByIdAndAuthenticationToken(
      @Param("id") Long id,
      @Param("authenticationToken") String authenticationToken,
      @Param("requestType") SelfServiceRequestType requestType);

  /**
   * Finds a self-service request by external authorization token and request type.
   *
   * @param externalAuthorizationToken external authorization token stored with the request
   * @param requestType expected request type
   * @return matching request, or {@code null} when no record matches
   */
  @Query(FIND_BY_EXTERNAL_AUTHORIZATION_TOKEN)
  SelfServiceRegistration getRequestByExternalAuthorizationToken(
      @Param("externalAuthorizationToken") String externalAuthorizationToken,
      @Param("requestType") SelfServiceRequestType requestType);

  /**
   * Deletes all self-service registration requests whose expiry timestamp is strictly before the
   * supplied cutoff.
   *
   * <p>This covers all request types (REGISTRATION, ENROLLMENT, PASSWORD_RESET) regardless of their
   * consumed status — an expired token is unusable whether or not it was consumed.
   *
   * @param cutoff reference timestamp; rows with {@code expiresAt < cutoff} are deleted
   * @return number of rows deleted
   */
  @Modifying
  @Transactional
  @Query(
      "DELETE FROM SelfServiceRegistration r WHERE r.expiresAt IS NOT NULL AND r.expiresAt < :cutoff")
  int deleteExpiredRequests(@Param("cutoff") LocalDateTime cutoff);

  /**
   * Finds the most recent self-service request for a specific client, request type, and
   * authentication token.
   *
   * <p>Uses a custom JPQL query to safely order by the primary key (id) descending, avoiding
   * potential PropertyReferenceExceptions for non-existent 'createdAt' fields.
   *
   * @param clientId the client identifier
   * @param requestType the expected request type
   * @param authenticationToken the authentication token (OTP)
   * @return the most recent matching request, or empty if none matches
   */
  @Query(
      "SELECT r FROM SelfServiceRegistration r WHERE r.client.id = :clientId AND r.requestType = :requestType AND r.authenticationToken = :authenticationToken ORDER BY r.id DESC")
  Optional<SelfServiceRegistration>
      findTopByClient_IdAndRequestTypeAndAuthenticationTokenOrderByCreatedAtDesc(
          @Param("clientId") Long clientId,
          @Param("requestType") SelfServiceRequestType requestType,
          @Param("authenticationToken") String authenticationToken);

  @Query(
      "SELECT r FROM SelfServiceRegistration r WHERE r.client.id = :clientId AND r.requestType = :requestType AND r.consumed = false ORDER BY r.id DESC")
  Optional<SelfServiceRegistration> findTopByClient_IdAndRequestTypeAndConsumedFalseOrderByIdDesc(
      @Param("clientId") Long clientId, @Param("requestType") SelfServiceRequestType requestType);

  // Bulk consumption: forzamos clearAutomatically + flushAutomatically para
  // que el 1er y 2do nivel de caché queden consistentes tras el UPDATE directo.
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "UPDATE SelfServiceRegistration r SET r.consumed = true "
          + "WHERE r.client.id = :clientId "
          + "AND r.requestType = :requestType "
          + "AND r.consumed = false "
          + "AND r.createdDate < :cutoff")
  int markOldOtpsAsConsumed(
      @Param("clientId") Long clientId,
      @Param("requestType") SelfServiceRequestType requestType,
      @Param("cutoff") LocalDateTime cutoff);
}
