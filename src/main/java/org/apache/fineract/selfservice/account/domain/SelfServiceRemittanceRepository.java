/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.account.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SelfServiceRemittanceRepository extends JpaRepository<SelfServiceRemittance, Long> {

  Optional<SelfServiceRemittance> findByPin(String pin);

  Optional<SelfServiceRemittance> findByExternalId(String externalId);

  Optional<SelfServiceRemittance> findByPinAndVendor(String pin, String vendor);

  List<SelfServiceRemittance> findByAppSelfServiceUserIdOrderByCreatedOnDesc(Long appSelfServiceUserId);

  List<SelfServiceRemittance> findByClientIdOrderByCreatedOnDesc(Long clientId);

  List<SelfServiceRemittance> findByStatus(String status);
}
