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
package com.lms.identity.query;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The cache epoch is what tells a console to throw away what it cached: it
 * must move when what the caller may see moves, and only then.
 */
class CacheEpochTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID UNIT = UUID.fromString("00000000-0000-0000-0000-0000000000c1");

    private static String epoch(UUID unit, String path, List<String> roles, List<String> permissions) {
        return UserQueryService.cacheEpoch(TENANT, USER, unit, path, roles, permissions);
    }

    @Test
    @DisplayName("the same rights give the same epoch, however often it is asked")
    void stable() {
        String first = epoch(UNIT, "/hq/", List.of("PLANNER"), List.of("ORDER_READ", "TRIP_READ"));
        assertThat(epoch(UNIT, "/hq/", List.of("PLANNER"), List.of("ORDER_READ", "TRIP_READ"))).isEqualTo(first);
        assertThat(first).hasSize(24);
    }

    @Test
    @DisplayName("a permission, a role grant or a move to another unit each change it")
    void changes() {
        String base = epoch(UNIT, "/hq/", List.of("PLANNER"), List.of("ORDER_READ"));
        assertThat(epoch(UNIT, "/hq/", List.of("PLANNER"), List.of("ORDER_READ", "ORDER_CREATE"))).isNotEqualTo(base);
        assertThat(epoch(UNIT, "/hq/", List.of("PLANNER", "AUDITOR"), List.of("ORDER_READ"))).isNotEqualTo(base);
        assertThat(epoch(UUID.randomUUID(), "/hq/north/", List.of("PLANNER"), List.of("ORDER_READ")))
                .isNotEqualTo(base);
        assertThat(UserQueryService.cacheEpoch(UUID.randomUUID(), USER, UNIT, "/hq/", List.of("PLANNER"),
                List.of("ORDER_READ"))).isNotEqualTo(base);
    }

    @Test
    @DisplayName("joining the parts cannot make two different grants collide")
    void separators() {
        assertThat(epoch(UNIT, "/hq/", List.of("A", "B"), List.of("C")))
                .isNotEqualTo(epoch(UNIT, "/hq/", List.of("A"), List.of("B", "C")));
    }
}
