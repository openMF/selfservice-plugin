/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.configuration.data;

import java.io.Serializable;
import java.util.Collections;
import java.util.Map;
import lombok.Builder;
import lombok.Value;
import org.apache.commons.lang3.StringUtils;

/**
 * Aggregated configuration for one external service (e.g. PaymentLinkService).
 * Backed by tenant-local rows in c_external_service / c_external_service_properties.
 */
@Value
@Builder
public class ExternalServiceConfigurationData implements Serializable {

  private static final long serialVersionUID = 1L;

  String serviceName;
  Map<String, String> properties;

  public Map<String, String> getProperties() {
    return properties == null ? Collections.emptyMap() : Collections.unmodifiableMap(properties);
  }

  public String get(String key) {
    return getProperties().get(key);
  }

  public String getOrDefault(String key, String defaultValue) {
    return getProperties().getOrDefault(key, defaultValue);
  }

  public boolean isEnabled() {
    return "true".equalsIgnoreCase(get("isEnabled"));
  }

  public String getHost() {
    return StringUtils.defaultString(get("host"));
  }

  public String getHeaderName() {
    return get("header");
  }

  public String getHeaderValue() {
    return get("headerValue");
  }
  
  public String getHttpMethod() {
    return get("httpMethod");
  }

  public boolean hasCustomHeader() {
    return StringUtils.isNotBlank(getHeaderName()) && getHeaderValue() != null;
  }
}