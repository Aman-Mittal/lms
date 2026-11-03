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
package com.lms.warehouse.command;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Which orders a wave holds, and in what sequence.
 *
 * <p>A plain association rather than part of the wave aggregate: a row is
 * written once when the wave is formed and never changed, and the question
 * asked most often runs the other way -- "is this order waiting in a wave?"
 * -- which an aggregate collection would not answer.
 */
@Component
class WaveMembers {

    private final JdbcClient jdbc;

    WaveMembers(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void add(UUID tenantId, UUID waveId, int seq, UUID orderId) {
        jdbc.sql("""
                        INSERT INTO pick_wave_order (wave_id, seq, tenant_id, order_id)
                        VALUES (:waveId, :seq, :tenantId, :orderId)
                        """)
                .param("waveId", waveId)
                .param("seq", seq)
                .param("tenantId", tenantId)
                .param("orderId", orderId)
                .update();
    }

    /** The wave's orders in the sequence they are served. */
    List<UUID> ordersOf(UUID waveId) {
        return jdbc.sql("""
                        SELECT order_id FROM pick_wave_order
                         WHERE wave_id = :waveId
                         ORDER BY seq
                        """)
                .param("waveId", waveId)
                .query(UUID.class)
                .list();
    }

    /** The number of a released wave the order is waiting in, if any. */
    Optional<String> awaitingWave(UUID orderId) {
        return jdbc.sql("""
                        SELECT w.wave_no
                          FROM pick_wave_order o
                          JOIN pick_wave w ON w.id = o.wave_id
                         WHERE o.order_id = :orderId
                           AND w.status = 'RELEASED'
                         LIMIT 1
                        """)
                .param("orderId", orderId)
                .query(String.class)
                .optional();
    }
}
