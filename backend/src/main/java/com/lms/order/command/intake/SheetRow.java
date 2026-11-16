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

/** One row of a parsed file: its number as a person sees it (the header is row 1) and its cells, left to right. */
record SheetRow(int number, List<String> cells) {

    SheetRow {
        cells = List.copyOf(cells);
    }

    String cell(int index) {
        return index >= 0 && index < cells.size() ? cells.get(index) : "";
    }

    boolean blank() {
        return cells.stream().allMatch(c -> c == null || c.isBlank());
    }
}
