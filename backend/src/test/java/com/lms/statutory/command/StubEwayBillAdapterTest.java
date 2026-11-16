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
package com.lms.statutory.command;

import java.time.Duration;
import java.time.Instant;

import com.lms.statutory.api.EwayBillAction;
import com.lms.statutory.api.EwayBillRequest;
import com.lms.statutory.api.EwayBillResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StubEwayBillAdapterTest {

    private static final Instant NOW = Instant.parse("2026-11-01T06:00:00Z");
    private final StubEwayBillAdapter stub = new StubEwayBillAdapter();

    private static EwayBillRequest generate(String document, Integer distanceKm, int sequence) {
        return new EwayBillRequest(EwayBillAction.GENERATE, document, null, null, null, distanceKm, 200, 24, null,
                null, "secret-ref:eway/branch-1", sequence, NOW);
    }

    private static EwayBillRequest on(EwayBillAction action, String billNo, String vehicle, Instant validUntil) {
        return new EwayBillRequest(action, "LR-1", billNo, vehicle, "reason", 100, 200, 24, validUntil, null, null, 0,
                NOW);
    }

    @Test
    @DisplayName("a generated bill has a twelve-digit number and a validity from the distance")
    void generation() {
        EwayBillResponse answer = stub.execute(generate("LR-100", 450, 0));
        assertThat(answer.accepted()).isTrue();
        assertThat(answer.billNo()).matches("\\d{12}");
        assertThat(answer.generatedAt()).isEqualTo(NOW);
        assertThat(answer.validUntil()).isEqualTo(NOW.plus(Duration.ofDays(3)));
    }

    @Test
    @DisplayName("the same document and sequence always give the same number; a later sequence a new one")
    void determinism() {
        String first = stub.execute(generate("LR-100", 100, 0)).billNo();
        assertThat(stub.execute(generate("LR-100", 100, 0)).billNo()).isEqualTo(first);
        assertThat(stub.execute(generate("LR-100", 100, 1)).billNo()).isNotEqualTo(first);
        assertThat(stub.execute(generate("LR-101", 100, 0)).billNo()).isNotEqualTo(first);
    }

    @Test
    @DisplayName("a document that mentions REFUSE is refused, whatever the action")
    void refusal() {
        for (EwayBillAction action : EwayBillAction.values()) {
            EwayBillRequest request = new EwayBillRequest(action, "LR-refuse-me", "123456789012", "MH12AB1234",
                    "reason", 100, 200, 24, NOW, null, null, 0, NOW);
            EwayBillResponse answer = stub.execute(request);
            assertThat(answer.accepted()).as(action.name()).isFalse();
            assertThat(answer.errorCode()).isEqualTo("stub-refused");
        }
    }

    @Test
    @DisplayName("fetch finds a bill unless the document is NOTFOUND")
    void fetch() {
        EwayBillRequest found = new EwayBillRequest(EwayBillAction.FETCH, "LR-7", null, null, null, 100, 200, 24, null,
                null, null, 0, NOW);
        assertThat(stub.execute(found).billNo()).isEqualTo(stub.execute(generate("LR-7", 100, 0)).billNo());
        EwayBillRequest missing = new EwayBillRequest(EwayBillAction.FETCH, "LR-NOTFOUND-7", null, null, null, 100, 200,
                24, null, null, null, 0, NOW);
        assertThat(stub.execute(missing).errorCode()).isEqualTo("stub-not-found");
    }

    @Test
    @DisplayName("extension moves the validity from the later of now and the present validity")
    void extension() {
        Instant later = NOW.plus(Duration.ofHours(5));
        assertThat(stub.execute(on(EwayBillAction.EXTEND, "123456789012", null, later)).validUntil())
                .isEqualTo(later.plus(Duration.ofHours(24)));
        Instant lapsed = NOW.minus(Duration.ofHours(5));
        assertThat(stub.execute(on(EwayBillAction.EXTEND, "123456789012", null, lapsed)).validUntil())
                .isEqualTo(NOW.plus(Duration.ofHours(24)));
    }

    @Test
    @DisplayName("part-B and vehicle change need a bill and a vehicle; cancel needs a bill")
    void incompleteRequests() {
        assertThat(stub.execute(on(EwayBillAction.PART_B, "123456789012", "MH12AB1234", NOW)).vehicleNo())
                .isEqualTo("MH12AB1234");
        assertThat(stub.execute(on(EwayBillAction.CHANGE_VEHICLE, "123456789012", null, NOW)).errorCode())
                .isEqualTo("stub-incomplete");
        assertThat(stub.execute(on(EwayBillAction.PART_B, null, "MH12AB1234", NOW)).errorCode())
                .isEqualTo("stub-incomplete");
        assertThat(stub.execute(on(EwayBillAction.CANCEL, "123456789012", null, NOW)).accepted()).isTrue();
        assertThat(stub.execute(on(EwayBillAction.CANCEL, " ", null, NOW)).errorCode()).isEqualTo("stub-incomplete");
    }
}
