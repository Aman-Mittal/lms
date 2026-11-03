/*
 * Copyright 2026 Aman Mittal
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.lms.tracking.query;

import java.util.List;
import java.util.UUID;

import com.lms.shared.error.ResourceNotFoundException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The desk's side of tracking links: which links a consignment has, and in what state. */
@Service
public class TrackingLinkQueryService {

    private final JdbcClient jdbc;

    public TrackingLinkQueryService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('TRACKING_LINK_MANAGE')")
    public List<TrackingLinkView> linksOf(UUID consignmentId) {
        if (jdbc.sql("SELECT 1 FROM consignment WHERE id = :id")
                .param("id", consignmentId).query(Integer.class).optional().isEmpty()) {
            throw new ResourceNotFoundException("Consignment", consignmentId);
        }
        return jdbc.sql("""
                        SELECT id, created_at, expires_at, revoked_at,
                               CASE WHEN revoked_at IS NOT NULL THEN 'REVOKED'
                                    WHEN expires_at <= now() THEN 'EXPIRED'
                                    ELSE 'ACTIVE' END AS status
                          FROM tracking_link
                         WHERE consignment_id = :c
                         ORDER BY created_at DESC, id
                        """)
                .param("c", consignmentId)
                .query(TrackingLinkView.class).list();
    }
}
