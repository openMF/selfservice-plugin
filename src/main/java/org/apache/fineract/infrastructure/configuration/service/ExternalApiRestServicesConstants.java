/**
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * <p>http://www.apache.org/licenses/LICENSE-2.0
 *
 * <p>Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
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
