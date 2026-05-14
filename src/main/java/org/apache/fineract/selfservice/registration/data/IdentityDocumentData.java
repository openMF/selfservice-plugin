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
package org.apache.fineract.selfservice.registration.data;

import java.io.Serial;
import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.Data;

/** Immutable data object represent client identity data. */
@Data
@AllArgsConstructor
public class IdentityDocumentData implements Serializable {

  @Serial private static final long serialVersionUID = 1L;

  private final Long documentId;
  private final String documentType;
  private final String documentDescription;
  private final Integer documentPosition;
  private final Boolean documentStatus;

  public static IdentityDocumentData singleItem(
      Long documentId,
      String documentType,
      String documentDescription,
      Integer documentPosition,
      Boolean documentStatus) {
    return new IdentityDocumentData(
        documentId, documentType, documentDescription, documentPosition, documentStatus);
  }
}
