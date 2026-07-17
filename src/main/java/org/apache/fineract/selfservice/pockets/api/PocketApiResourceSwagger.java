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
package org.apache.fineract.selfservice.pockets.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Set;

/** Swagger request/response models for {@link PocketApiResource}. */
public final class PocketApiResourceSwagger {

  private PocketApiResourceSwagger() {}

  @Schema(description = "PostLinkDelinkAccountsToFromPocketRequest")
  public static final class PostLinkDelinkAccountsToFromPocketRequest {

    private PostLinkDelinkAccountsToFromPocketRequest() {}

    static final class GetPocketAccountDetail {

      private GetPocketAccountDetail() {}

      @Schema(example = "11")
      public Long accountId;

      @Schema(example = "LOAN")
      public String accountType;
    }

    public Set<GetPocketAccountDetail> accountsDetail;
  }

  @Schema(description = "PostLinkDelinkAccountsToFromPocketResponse")
  public static final class PostLinkDelinkAccountsToFromPocketResponse {

    private PostLinkDelinkAccountsToFromPocketResponse() {}

    @Schema(example = "6")
    public Long resourceId;
  }

  @Schema(description = "GetAccountsLinkedToPocketResponse")
  public static final class GetAccountsLinkedToPocketResponse {

    private GetAccountsLinkedToPocketResponse() {}

    static final class GetPocketLoanAccounts {

      private GetPocketLoanAccounts() {}

      @Schema(example = "6")
      public Long pocketId;

      @Schema(example = "11")
      public Long accountId;

      @Schema(example = "1")
      public Integer accountType;

      @Schema(example = "000000011")
      public String accountNumber;

      @Schema(example = "10")
      public Long id;
    }

    static final class GetPocketSavingAccounts {

      private GetPocketSavingAccounts() {}

      @Schema(example = "6")
      public Long pocketId;

      @Schema(example = "2")
      public Long accountId;

      @Schema(example = "2")
      public Integer accountType;

      @Schema(example = "000000002")
      public String accountNumber;

      @Schema(example = "11")
      public Long id;
    }

    static final class GetPocketShareAccounts {

      private GetPocketShareAccounts() {}

      @Schema(example = "6")
      public Long pocketId;

      @Schema(example = "3")
      public Long accountId;

      @Schema(example = "3")
      public Integer accountType;

      @Schema(example = "000000003")
      public String accountNumber;

      @Schema(example = "12")
      public Long id;
    }

    public Set<GetPocketLoanAccounts> loanAccounts;
    public Set<GetPocketSavingAccounts> savingsAccounts;
    public Set<GetPocketShareAccounts> shareAccounts;
  }
}
