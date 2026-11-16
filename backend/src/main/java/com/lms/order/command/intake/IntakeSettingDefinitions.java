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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.lms.settings.api.ScopeType;
import com.lms.settings.api.SettingContributor;
import com.lms.settings.api.SettingDefinition;
import com.lms.settings.api.SettingKey;
import com.lms.settings.api.SettingType;
import org.springframework.stereotype.Component;

/**
 * The column mapping of an order file: which header holds each field. A
 * customer whose spreadsheet says "Pick-up point" where the default says
 * "origin" has its own value; any field it leaves out keeps the default
 * header. Headers are compared ignoring case, blanks and punctuation, so
 * "Order Ref" and "order_ref" are the same header.
 */
@Component
public class IntakeSettingDefinitions implements SettingContributor {

    public static final SettingKey<Map<String, Object>> COLUMN_MAPPING =
            SettingKey.json("order.intake.columnMapping");

    /** Each field of a row, with the header the default mapping expects for it. */
    static final Map<String, String> DEFAULT_HEADERS = defaults();

    private static Map<String, String> defaults() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(IntakeField.ORDER_REF, "order_ref");
        m.put(IntakeField.ORIGIN, "origin");
        m.put(IntakeField.DESTINATION, "destination");
        m.put(IntakeField.PICKUP_DATE, "pickup_date");
        m.put(IntakeField.DELIVERY_DATE, "delivery_date");
        m.put(IntakeField.LOAD_TYPE, "load_type");
        m.put(IntakeField.MATERIAL_CODE, "material_code");
        m.put(IntakeField.MATERIAL, "material");
        m.put(IntakeField.QUANTITY, "quantity");
        m.put(IntakeField.UOM, "uom");
        m.put(IntakeField.WEIGHT_KG, "weight_kg");
        m.put(IntakeField.CONSIGNEE, "consignee");
        return java.util.Collections.unmodifiableMap(m);
    }

    @Override
    public List<SettingDefinition> definitions() {
        SettingDefinition.Builder mapping = SettingDefinition.of(COLUMN_MAPPING, "Order file column mapping",
                        "The header of each column in a customer's order file, as {\"origin\": \"Pick-up point\", ...}."
                                + " Fields: " + String.join(", ", DEFAULT_HEADERS.keySet()) + ". A field left out"
                                + " uses the default header. Headers are matched ignoring case, blanks and punctuation.")
                .defaultValue(Map.copyOf(DEFAULT_HEADERS))
                .alsoAt(ScopeType.ORG_UNIT, ScopeType.CUSTOMER);
        DEFAULT_HEADERS.keySet().forEach(field -> mapping.property(field, SettingType.STRING, false));
        return List.of(mapping.build());
    }
}
