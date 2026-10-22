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
package org.apache.fineract.selfservice.security.api;

import io.swagger.v3.oas.annotations.media.Schema;

/** Created by Chirag Gupta on 12/20/17. */
final class SelfUserApiResourceSwagger {

  private SelfUserApiResourceSwagger() {}

  @Schema(description = "PutSelfUserRequest")
  public static final class PutSelfUserRequest {

    private PutSelfUserRequest() {}

    @Schema(example = "Abcd1234")
    public String password;

    @Schema(example = "Abcd1234")
    public String repeatPassword;
  }

  @Schema(description = "PutSelfUserResponse")
  public static final class PutSelfUserResponse {

    private PutSelfUserResponse() {}

    static final class PutSelfUserChanges {

      private PutSelfUserChanges() {}

      @Schema(example = "true")
      public Boolean password;
    }

    @Schema(example = "1")
    public Long officeId;

    @Schema(example = "6")
    public Long resourceId;

    public PutSelfUserChanges changes;
  }
}
