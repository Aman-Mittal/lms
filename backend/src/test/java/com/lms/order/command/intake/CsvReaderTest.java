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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CsvReaderTest {

    @Test
    @DisplayName("rows are numbered from the header, quotes hold delimiters and line breaks, a doubled quote is one")
    void quoting() {
        List<SheetRow> rows = CsvReader.read("a,b,c\r\n1,\"x,y\",\"line1\nline2\"\r\n\"say \"\"hi\"\"\",,3\r\n");
        assertThat(rows).hasSize(3);
        assertThat(rows.get(0).cells()).containsExactly("a", "b", "c");
        assertThat(rows.get(1).number()).isEqualTo(2);
        assertThat(rows.get(1).cells()).containsExactly("1", "x,y", "line1\nline2");
        assertThat(rows.get(2).cells()).containsExactly("say \"hi\"", "", "3");
    }

    @Test
    @DisplayName("the delimiter is whichever the header uses most, and a byte-order mark is dropped")
    void delimiters() {
        char bom = 0xFEFF;
        assertThat(CsvReader.read(bom + "a;b;c\n1;2;3").get(1).cells()).containsExactly("1", "2", "3");
        assertThat(CsvReader.read("a\tb\n1\t2").get(1).cells()).containsExactly("1", "2");
        assertThat(CsvReader.read(bom + "a,b").get(0).cells()).containsExactly("a", "b");
    }

    @Test
    @DisplayName("a last line without a break is read, and blank lines keep their numbers")
    void lastLineAndBlanks() {
        List<SheetRow> rows = CsvReader.read("h\n\nx");
        assertThat(rows).extracting(SheetRow::number).containsExactly(1, 2, 3);
        assertThat(rows.get(1).blank()).isTrue();
        assertThat(rows.get(2).cells()).containsExactly("x");
    }
}
