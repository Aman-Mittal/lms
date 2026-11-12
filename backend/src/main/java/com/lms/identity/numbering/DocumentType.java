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
package com.lms.identity.numbering;

/**
 * The kinds of document a number series can number.
 *
 * <p>Mirrors {@code number_series_type_valid}. Today orders, lorry receipts
 * and dockets draw from a series when one is configured; the others can be
 * configured, previewed, issued by hand and verified, ready for the module
 * that numbers them to start asking.
 */
public enum DocumentType {
    /** A customer order; without a series, {@code ORD-<yyyy>-<6 digits>}. */
    ORDER,
    /** A full-truckload consignment note (lorry receipt); without a series, a random {@code LR-} reference. */
    CONSIGNMENT_NOTE,
    /** A part-load docket; without a series, a random {@code DKT-} reference. */
    DOCKET,
    TRIP,
    GATE_PASS,
    LOAD,
    INVOICE,
    /** A client contract draft; without a series, the code its maker types. */
    CONTRACT
}
