package org.apache.fineract.selfservice.account.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SelfServicePucAddAccountAuditRepository
        extends JpaRepository<SelfServicePucAddAccountAudit, Long> {
}
