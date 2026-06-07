/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.selfservice.security.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * JDBC-backed implementation of {@link SelfServiceOfficeAddressReadService}.
 *
 * <p>Resolves the country by traversing: m_client → m_selfservice_office_address → m_address → m_code_value.
 * Returns an empty string for any missing link rather than throwing, as the country
 * is optional/enrichment data for the authentication response.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class SelfServiceOfficeAddressReadServiceImpl implements SelfServiceOfficeAddressReadService {

    private static final String COUNTRY_QUERY =
            "SELECT cv.code_value "
                    + "FROM m_client c "
                    + "INNER JOIN m_office_address oa ON c.office_id = oa.office_id AND oa.is_active = true "
                    + "INNER JOIN m_address a ON oa.address_id = a.id "
                    + "INNER JOIN m_code_value cv ON a.country_id = cv.id "
                    + "WHERE c.id = ?";

    private final JdbcTemplate jdbcTemplate;

    /**
     * {@inheritDoc}
     */
    @Override
    public String retrieveOfficeCountryByClientId(final Long clientId) {
        if (clientId == null) {
            log.debug("clientId is null; returning empty country");
            return "";
        }
        try {
            final String country = jdbcTemplate.queryForObject(COUNTRY_QUERY, String.class, clientId);
            return country != null ? country : "";
        } catch (final EmptyResultDataAccessException e) {
            log.debug("No active office address with country found for clientId={}; returning empty", clientId);
            return "";
        } catch (final Exception e) {
            log.warn("Unexpected error retrieving country for clientId={}; returning empty", clientId, e);
            return "";
        }
    }
}