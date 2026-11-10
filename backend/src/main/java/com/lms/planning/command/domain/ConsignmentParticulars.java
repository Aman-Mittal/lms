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
package com.lms.planning.command.domain;

import java.time.Instant;

/**
 * What is said about a consignment, as opposed to what it carries.
 *
 * <p>Every part is optional. {@code clientReference} is the client's own name
 * for the consignment -- an order or shipment number from their system -- and
 * is unique within the tenant when set; the others are free text the
 * consignee's paperwork needs.
 *
 * @param deliveryWindowFrom the earliest the consignee will receive it
 * @param deliveryWindowTo   the latest; never before {@code deliveryWindowFrom}
 */
public record ConsignmentParticulars(
        String contactName,
        String contactPhone,
        String consigneeReference,
        String clientReference,
        Instant deliveryWindowFrom,
        Instant deliveryWindowTo,
        String remarks) {

    /** Nothing said yet: what a consignment is generated with. */
    public static final ConsignmentParticulars NONE =
            new ConsignmentParticulars(null, null, null, null, null, null, null);

    /**
     * The same particulars without the client's reference: what a part of a
     * split consignment inherits, since the reference names one consignment.
     */
    public ConsignmentParticulars withoutClientReference() {
        return new ConsignmentParticulars(contactName, contactPhone, consigneeReference, null,
                deliveryWindowFrom, deliveryWindowTo, remarks);
    }
}
