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

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.zip.CRC32;

import com.lms.statutory.api.EwayBillAdapter;
import com.lms.statutory.api.EwayBillRequest;
import com.lms.statutory.api.EwayBillResponse;
import org.springframework.stereotype.Component;

/**
 * A deterministic stand-in for a statutory system, for tests and the demo. It
 * holds nothing: a bill number is a function of the document reference and the
 * bill's sequence, the validity a function of the distance and the daily
 * allowance of the request, and every other answer follows from the request.
 *
 * <p>It can be made to refuse: a document reference containing {@code REFUSE}
 * is refused whatever the action, and one containing {@code NOTFOUND} is not
 * found by FETCH.
 */
@Component
public class StubEwayBillAdapter implements EwayBillAdapter {

    /** The adapter setting value. */
    public static final String CODE = StatutorySettingDefinitions.STUB;

    /** A bill number is twelve digits, as the statutory system's are. */
    private static final long NUMBER_SPACE = 100_000_000_000L;

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public EwayBillResponse execute(EwayBillRequest request) {
        String reference = request.documentRef() == null ? "" : request.documentRef();
        if (reference.toUpperCase(Locale.ROOT).contains("REFUSE")) {
            return EwayBillResponse.refused("stub-refused", "The stub refuses documents that mention REFUSE");
        }
        return switch (request.action()) {
            case GENERATE -> generated(request, reference);
            case FETCH -> reference.toUpperCase(Locale.ROOT).contains("NOTFOUND")
                    ? EwayBillResponse.refused("stub-not-found", "The stub holds no bill for this document")
                    : generated(request, reference);
            case PART_B, CHANGE_VEHICLE -> {
                if (isBlank(request.billNo()) || isBlank(request.vehicleNo())) {
                    yield EwayBillResponse.refused("stub-incomplete", "A bill number and a vehicle are required");
                }
                yield EwayBillResponse.accepted(request.billNo(), null, request.currentValidUntil(),
                        request.vehicleNo());
            }
            case EXTEND -> {
                if (isBlank(request.billNo())) {
                    yield EwayBillResponse.refused("stub-incomplete", "A bill number is required");
                }
                Instant from = request.currentValidUntil() != null && request.currentValidUntil().isAfter(request.now())
                        ? request.currentValidUntil() : request.now();
                yield EwayBillResponse.accepted(request.billNo(), null,
                        from.plus(Duration.ofHours(request.extensionHours())), request.vehicleNo());
            }
            case CANCEL -> isBlank(request.billNo())
                    ? EwayBillResponse.refused("stub-incomplete", "A bill number is required")
                    : EwayBillResponse.accepted(request.billNo(), null, null, null);
        };
    }

    private static EwayBillResponse generated(EwayBillRequest request, String reference) {
        int distance = request.distanceKm() == null ? 1 : request.distanceKm();
        return EwayBillResponse.accepted(numberFor(reference, request.sequence()), request.now(),
                EwayBillRules.validUntil(request.now(), distance, request.kmPerDay()), request.vehicleNo());
    }

    /** Twelve digits from the document and the sequence; the same input always gives the same number. */
    static String numberFor(String reference, int sequence) {
        CRC32 forward = new CRC32();
        forward.update((reference + "#" + sequence).getBytes(StandardCharsets.UTF_8));
        CRC32 backward = new CRC32();
        backward.update(new StringBuilder(reference).reverse().append('#').append(sequence * 31 + 7).toString()
                .getBytes(StandardCharsets.UTF_8));
        long mixed = (forward.getValue() << 20) ^ backward.getValue();
        return String.format(Locale.ROOT, "%012d", Math.floorMod(mixed, NUMBER_SPACE));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
