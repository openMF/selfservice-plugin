/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.stepup.service;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;

/**
 * Identifies the login session of a request, so a step-up grant can be bound to it.
 *
 * <p>The self-service API currently authenticates with a bearer-like session token that is sent in
 * the {@code Authorization} header for compatibility (see {@link
 * AuthorizationHeaderStepUpSessionResolver}). The plugin is moving to an OIDC compatible security
 * model; step-up only needs a stable identifier of "this session", so supporting the new model
 * means providing another implementation of this interface (for example one based on the access
 * token or its {@code sid}/{@code jti} claim) and nothing else in the step-up module changes.
 *
 * <p>Implementations must return a digest (never the raw credential): the value is stored and
 * compared as is.
 */
public interface StepUpSessionResolver {

  /**
   * Digest of the session identifier of this request, or empty when the request is not tied to a
   * session (for example credential based authentication with a user name and password).
   */
  Optional<String> sessionHash(HttpServletRequest request);
}
