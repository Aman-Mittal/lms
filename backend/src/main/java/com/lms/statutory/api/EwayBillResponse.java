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
package com.lms.statutory.api;

import java.time.Instant;

/**
 * What an adapter answers: accepted, with the bill's present facts, or refused
 * with a code and a message. An adapter that cannot answer at all (a timeout,
 * a transport failure) throws, and the caller records that as an error.
 *
 * @param accepted     whether the statutory system did what was asked
 * @param billNo       the bill's number, on acceptance
 * @param generatedAt  when the bill was generated, on acceptance of GENERATE and FETCH
 * @param validUntil   the bill's validity after the call, on acceptance (null after a CANCEL)
 * @param vehicleNo    the vehicle on Part-B after the call, when there is one
 * @param errorCode    a stable code when refused
 * @param errorMessage why, in words
 */
public record EwayBillResponse(boolean accepted, String billNo, Instant generatedAt, Instant validUntil,
                               String vehicleNo, String errorCode, String errorMessage) {

    public static EwayBillResponse accepted(String billNo, Instant generatedAt, Instant validUntil,
                                            String vehicleNo) {
        return new EwayBillResponse(true, billNo, generatedAt, validUntil, vehicleNo, null, null);
    }

    public static EwayBillResponse refused(String code, String message) {
        return new EwayBillResponse(false, null, null, null, null, code, message);
    }
}
