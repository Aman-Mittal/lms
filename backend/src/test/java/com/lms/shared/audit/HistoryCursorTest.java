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
package com.lms.shared.audit;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import com.lms.shared.error.BusinessRuleViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HistoryCursorTest {

    @Test
    @DisplayName("a cursor round-trips the row's time to the microsecond and its id")
    void roundTrip() {
        UUID id = UUID.randomUUID();
        Instant at = Instant.parse("2026-11-04T10:15:30.123456Z");
        AuditHistoryQueryService.Cursor cursor = new AuditHistoryQueryService.Cursor(
                at.atOffset(ZoneOffset.UTC), id);

        AuditHistoryQueryService.Cursor parsed = AuditHistoryQueryService.Cursor.parse(cursor.encode());

        assertThat(parsed.occurredAt().toInstant()).isEqualTo(at);
        assertThat(parsed.id()).isEqualTo(id);
    }

    @Test
    @DisplayName("no cursor is the first page")
    void absent() {
        assertThat(AuditHistoryQueryService.Cursor.parse(null)).isNull();
        assertThat(AuditHistoryQueryService.Cursor.parse(" ")).isNull();
    }

    @Test
    @DisplayName("a cursor the listing did not issue is refused as invalid-cursor")
    void forged() {
        for (String forged : new String[] {"nonsense", "2026-11-04T10:15:30Z_not-a-uuid", "_"}) {
            assertThatThrownBy(() -> AuditHistoryQueryService.Cursor.parse(forged))
                    .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                            e -> assertThat(e.code()).isEqualTo("invalid-cursor"));
        }
    }
}
