/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.configuration.data;

import java.io.Serializable;
import lombok.Builder;
import lombok.Value;

/** Single name/value pair from {@code c_external_service_properties}. */
@Value
@Builder
public class ExternalServicePropertyData implements Serializable {

  private static final long serialVersionUID = 1L;

  String name;
  String value;
}
