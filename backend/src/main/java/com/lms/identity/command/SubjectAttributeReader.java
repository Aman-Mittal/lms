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
package com.lms.identity.command;

import java.sql.Array;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import com.lms.identity.access.SubjectAttributes;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Reads the attributes a user's access token carries.
 *
 * <p>Kept off the {@code AppUser} aggregate on purpose. They are read once per
 * token and written by an administrator, never by the sign-in path that saves
 * the aggregate -- and an aggregate that carried them would have to thread
 * four more fields through every one of its copy constructors for no
 * behaviour of its own.
 *
 * <p>Runs inside the caller's transaction, so row-level security applies.
 */
@Component
public class SubjectAttributeReader {

    private final JdbcClient jdbc;

    public SubjectAttributeReader(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public SubjectAttributes of(UUID userId) {
        return jdbc.sql("""
                        SELECT region, freight_modes, partner_id, approval_limit
                          FROM app_user
                         WHERE id = :id
                        """)
                .param("id", userId)
                .query((rs, row) -> new SubjectAttributes(
                        rs.getString("region"),
                        modes(rs.getArray("freight_modes")),
                        rs.getObject("partner_id", UUID.class),
                        rs.getBigDecimal("approval_limit")))
                .optional()
                .orElse(SubjectAttributes.NONE);
    }

    private static List<String> modes(Array array) throws SQLException {
        if (array == null) {
            return null;
        }
        return Arrays.stream((Object[]) array.getArray()).map(String::valueOf).toList();
    }
}
