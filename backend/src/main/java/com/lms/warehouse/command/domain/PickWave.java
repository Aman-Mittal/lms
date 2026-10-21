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
package com.lms.warehouse.command.domain;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import com.lms.shared.error.BusinessRuleViolationException;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Several outbound orders of one warehouse, picked together in one pass.
 *
 * <p>Released when it is formed and picked once. Which orders it holds, and
 * in what order they are served, is kept beside it in {@code pick_wave_order};
 * what each order was given is recorded on the order's own lines, so a
 * shortfall in a wave is always attributed to the order that suffered it.
 */
@Table("pick_wave")
public record PickWave(
        @Id UUID id,
        UUID tenantId,
        UUID warehouseId,
        String waveNo,
        Status status,
        String pickedBy,
        Instant pickedAt,
        @Version Long version,
        Instant createdAt) {

    public enum Status {
        /** Formed and waiting for the picker. Its orders are not picked singly. */
        RELEASED,
        PICKED
    }

    public static PickWave release(UUID tenantId, UUID warehouseId, Instant at) {
        return new PickWave(UUID.randomUUID(), tenantId, warehouseId, newWaveNo(),
                Status.RELEASED, null, null, null, at);
    }

    public PickWave picked(String actor, Instant at) {
        if (status != Status.RELEASED) {
            throw new BusinessRuleViolationException("pick-wave-already-picked",
                    "Wave " + waveNo + " was already picked at " + pickedAt);
        }
        return new PickWave(id, tenantId, warehouseId, waveNo, Status.PICKED, actor, at, version,
                createdAt);
    }

    /** Random for the same reason handling-unit numbers are: no per-tenant counter to lock. */
    private static String newWaveNo() {
        return "WAVE-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }
}
