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
package com.lms.delivery.command.domain;

/** Where a consignment's proof of delivery stands. */
public enum ProofStatus {
    /** A draft, started and not yet fed or submitted; no code has been issued. */
    PENDING,
    /** A one-time code was issued and has not been matched. */
    OTP_ISSUED,
    /** The code matched and the proof was recorded in one step (the original route). */
    CONFIRMED,
    /**
     * A draft fed with lines, documents and times was submitted, with a code
     * or keyed from paper. Proves the delivery exactly as CONFIRMED does.
     */
    SUBMITTED,
    /** The consignee took nothing. Proves nothing; the draft may still be fed and submitted. */
    NOT_RECEIVED,
    /**
     * A proof was reversed through an approved request (V53). The
     * recipient and time stay on file; a fresh code may be issued and a new
     * proof recorded.
     */
    REVERSED;

    /** Whether this status proves the delivery: CONFIRMED or SUBMITTED. */
    public boolean proves() {
        return this == CONFIRMED || this == SUBMITTED;
    }
}
