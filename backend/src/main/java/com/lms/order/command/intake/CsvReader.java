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
import java.util.List;

/**
 * Reads delimited text: RFC 4180 quoting (a doubled quote inside a quoted
 * field, line breaks inside one), a byte-order mark, and a comma, semicolon
 * or tab as the delimiter, whichever the header row uses most.
 */
final class CsvReader {

    private static final char BYTE_ORDER_MARK = 0xFEFF;

    private CsvReader() {
    }

    static List<SheetRow> read(String text) {
        String body = text.startsWith("﻿") ? text.substring(1) : text;
        char delimiter = delimiterOf(body);
        List<SheetRow> rows = new ArrayList<>();
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        int number = 1;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < body.length() && body.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(c);
                }
            } else if (c == '"' && cell.isEmpty()) {
                quoted = true;
            } else if (c == delimiter) {
                cells.add(cell.toString());
                cell.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < body.length() && body.charAt(i + 1) == '\n') {
                    i++;
                }
                cells.add(cell.toString());
                cell.setLength(0);
                rows.add(new SheetRow(number++, cells));
                cells = new ArrayList<>();
            } else {
                cell.append(c);
            }
        }
        if (!cell.isEmpty() || !cells.isEmpty()) {
            cells.add(cell.toString());
            rows.add(new SheetRow(number, cells));
        }
        return rows;
    }

    /** The delimiter that occurs most in the first line, outside quotes; a comma when none does. */
    private static char delimiterOf(String body) {
        int comma = 0;
        int semicolon = 0;
        int tab = 0;
        boolean quoted = false;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (!quoted && (c == '\n' || c == '\r')) {
                break;
            } else if (!quoted) {
                switch (c) {
                    case ',' -> comma++;
                    case ';' -> semicolon++;
                    case '\t' -> tab++;
                    default -> {
                    }
                }
            }
        }
        if (semicolon > comma && semicolon >= tab) {
            return ';';
        }
        return tab > comma ? '\t' : ',';
    }
}
