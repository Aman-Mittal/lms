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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.lms.shared.error.BusinessRuleViolationException;

/**
 * Which column of a file holds which field: the customer's mapping laid over
 * the default headers, matched against the file's header row ignoring case,
 * blanks and punctuation.
 */
final class IntakeColumns {

    private final Map<String, Integer> index;

    private IntakeColumns(Map<String, Integer> index) {
        this.index = index;
    }

    /**
     * @param mapping the effective setting: field to header, a field left out or blank keeping the default
     * @throws BusinessRuleViolationException {@code intake-columns-missing} naming every required field not found
     */
    static IntakeColumns of(SheetRow header, Map<String, Object> mapping) {
        Map<String, Integer> byHeader = new HashMap<>();
        for (int i = 0; i < header.cells().size(); i++) {
            byHeader.putIfAbsent(normalise(header.cell(i)), i);
        }
        Map<String, Integer> index = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        IntakeSettingDefinitions.DEFAULT_HEADERS.forEach((field, defaultHeader) -> {
            Object stated = mapping == null ? null : mapping.get(field);
            String wanted = stated == null || stated.toString().isBlank() ? defaultHeader : stated.toString();
            Integer column = byHeader.get(normalise(wanted));
            if (column != null) {
                index.put(field, column);
            } else if (IntakeField.REQUIRED.contains(field)) {
                missing.add(field + " (header \"" + wanted + "\")");
            }
        });
        if (!missing.isEmpty()) {
            throw new BusinessRuleViolationException("intake-columns-missing",
                    "The file has no column for: " + String.join(", ", missing));
        }
        return new IntakeColumns(index);
    }

    /** The trimmed value of the field in the row; blank when the file has no such column. */
    String value(SheetRow row, String field) {
        Integer column = index.get(field);
        return column == null ? "" : row.cell(column).strip();
    }

    static String normalise(String header) {
        StringBuilder letters = new StringBuilder();
        for (char c : header.toLowerCase(Locale.ROOT).toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                letters.append(c);
            }
        }
        return letters.toString();
    }
}
