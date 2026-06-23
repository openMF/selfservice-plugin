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
package org.apache.fineract.selfservice.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

@Component
@Conditional(SelfServiceModuleIsEnabledCondition.class)
@Slf4j
public class SelfServiceWarning implements InitializingBean {

  @Override
  public void afterPropertiesSet() throws Exception {
    log.warn("*******************************************************");
    log.warn("*                                                     *");
    log.warn("*            DO NOT USE THIS IN PRODUCTION!           *");
    log.warn("*           WITHOUT SECURITY BEST PRACTICES           *");
    log.warn("*           Self Service Plugin capabilities          *");
    log.warn("*                for Apache Fineract                  *");
    log.warn("*                   ARE NOT SAFE!                     *");
    log.warn("*                                                     *");
    log.warn("*******************************************************");
  }
}
