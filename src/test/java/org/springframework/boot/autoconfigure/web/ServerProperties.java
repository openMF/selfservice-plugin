/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.springframework.boot.autoconfigure.web;

/**
 * Test-only compatibility shim. The pinned Fineract artifacts were compiled against Spring Boot 3,
 * where this class lived in {@code org.springframework.boot.autoconfigure.web}. Spring Boot 4 moved
 * it to {@code org.springframework.boot.web.server.autoconfigure}, so Fineract's {@code
 * SecurityConfig} cannot even be introspected in the security wiring tests ({@code
 * NoClassDefFoundError}). Remove this class when the Fineract dependency is built against Spring
 * Boot 4.
 */
public class ServerProperties extends org.springframework.boot.web.server.autoconfigure.ServerProperties {}
