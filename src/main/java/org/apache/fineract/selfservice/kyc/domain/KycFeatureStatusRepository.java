/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.fineract.selfservice.kyc.domain;


import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import org.apache.fineract.kyc.domain.KycFeatureStatus;

@Repository
public interface KycFeatureStatusRepository extends JpaRepository<KycFeatureStatus, Long> {

    /**
     * Finds the feature status for the latest KYC verification of a given client.
     * Uses the verification with the highest ID (most recent).
     */
    @Query("SELECT fs FROM KycFeatureStatus fs " +
           "JOIN fs.kycVerification v " +
           "WHERE v.clientId = :clientId " +
           "ORDER BY v.id DESC " +
           "LIMIT 1")
    Optional<KycFeatureStatus> findLatestByClientId(@Param("clientId") Long clientId);

    /**
     * Finds the feature status for the latest APPROVED KYC verification.
     */
    @Query("SELECT fs FROM KycFeatureStatus fs " +
           "JOIN fs.kycVerification v " +
           "WHERE v.clientId = :clientId " +
           "AND v.kycStatus = 'Approved' " +
           "ORDER BY v.id DESC " +
           "LIMIT 1")
    Optional<KycFeatureStatus> findLatestApprovedByClientId(@Param("clientId") Long clientId);

    Optional<KycFeatureStatus> findByKycVerificationId(Long kycVerificationId);
}