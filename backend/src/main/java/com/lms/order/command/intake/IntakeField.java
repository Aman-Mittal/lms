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

package com.lms.order.command.intake;

import java.util.List;

/** The fields of one row of an order file; a name is both the mapping key and the field's label in a result. */
final class IntakeField {

    static final String ORDER_REF = "orderRef";
    static final String ORIGIN = "origin";
    static final String DESTINATION = "destination";
    static final String PICKUP_DATE = "pickupDate";
    static final String DELIVERY_DATE = "deliveryDate";
    static final String LOAD_TYPE = "loadType";
    static final String MATERIAL_CODE = "materialCode";
    static final String MATERIAL = "material";
    static final String QUANTITY = "quantity";
    static final String UOM = "uom";
    static final String WEIGHT_KG = "weightKg";
    static final String CONSIGNEE = "consignee";

    /** Without a value in these a row cannot make a line. */
    static final List<String> REQUIRED = List.of(ORDER_REF, ORIGIN, DESTINATION, QUANTITY);

    private IntakeField() {
    }
}
