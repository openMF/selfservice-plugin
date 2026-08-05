/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.branding.security;

import org.apache.fineract.branding.starter.TenantBrandingSecurityConfiguration;
import org.apache.fineract.selfservice.security.SelfServiceSecurityTestConfig;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Stands all three chains up together, which is the only arrangement in which the branding chain's
 * ordering can be asserted: on its own it would claim branding trivially.
 *
 * <p>Reuses {@link SelfServiceSecurityTestConfig} for the mocked collaborators rather than
 * duplicating them, and adds the branding chain on top.
 */
@Configuration
@Import({SelfServiceSecurityTestConfig.class, TenantBrandingSecurityConfiguration.class})
public class TenantBrandingSecurityTestConfig {}
