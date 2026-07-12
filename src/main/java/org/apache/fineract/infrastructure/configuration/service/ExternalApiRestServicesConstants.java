/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.infrastructure.configuration.service;

import java.util.HashSet;
import java.util.Set;

public final class ExternalApiRestServicesConstants {

  private ExternalApiRestServicesConstants() {}

  public static final String NATIONAL_ID_SERVICE_NAME = "NationalIdService";
  public static final String NATIONAL_ID_HOST = "host";
  public static final String NATIONAL_ID_HEADER = "header";
  public static final String NATIONAL_ID_HEADER_VALUE = "headerValue";
  public static final String NATIONAL_ID_HTTP_METHOD = "httpMethod";
  public static final String NATIONAL_ID_PARAMETER_NAME = "parameterName";
  public static final String NATIONAL_ID_IS_ENABLED = "isEnabled";

  public static final String NOTIFICATION_SERVICE_NAME = "NotificationsService";
  public static final String NOTIFICATION_HOST = "host";
  public static final String NOTIFICATION_HEADER = "header";
  public static final String NOTIFICATION_HEADER_VALUE = "headerValue";
  public static final String NOTIFICATION_HTTP_METHOD = "httpMethod";
  public static final String NOTIFICATION_IS_EMAIL = "isEmail";
  public static final String NOTIFICATION_IS_SMS = "isSms";
  public static final String NOTIFICATION_IS_WHATSAPP = "isWhatsapp";
  public static final String NOTIFICATION_IS_ENABLED = "isEnabled";

  public enum ExternalservicePropertiesJSONinputParams {
    EXTERNAL_SERVICE_ID("external_service_id"), //
    NAME("name"), //
    VALUE("value"); //

    private final String value;

    ExternalservicePropertiesJSONinputParams(final String value) {
      this.value = value;
    }

    private static final Set<String> values = new HashSet<>();

    static {
      for (final ExternalservicePropertiesJSONinputParams type :
          ExternalservicePropertiesJSONinputParams.values()) {
        values.add(type.value);
      }
    }

    public static Set<String> getAllValues() {
      return values;
    }

    @Override
    public String toString() {
      return name().toString().replaceAll("_", " ");
    }

    public String getValue() {
      return this.value;
    }
  }
}
