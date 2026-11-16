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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.lms.shared.error.BusinessRuleViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class XlsxReaderTest {

    static byte[] workbook(String sharedStrings, String sheet) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            add(zip, "xl/workbook.xml", "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\""
                    + " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                    + "<sheets><sheet name=\"Orders\" sheetId=\"1\" r:id=\"rId7\"/></sheets></workbook>");
            add(zip, "xl/_rels/workbook.xml.rels", "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId7\" Type=\"worksheet\" Target=\"worksheets/sheet2.xml\"/></Relationships>");
            if (sharedStrings != null) {
                add(zip, "xl/sharedStrings.xml", "<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                        + sharedStrings + "</sst>");
            }
            add(zip, "xl/worksheets/sheet2.xml", "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                    + "<sheetData>" + sheet + "</sheetData></worksheet>");
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        return out.toByteArray();
    }

    private static void add(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    @Test
    @DisplayName("shared strings, inline strings, numbers and booleans are read, a skipped cell is blank, and the first sheet is found through its relationship")
    void cells() {
        byte[] file = workbook("<si><t>order_ref</t></si><si><t>origin</t></si><si><r><t>Mum</t></r><r><t>bai</t></r></si>",
                "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"B1\" t=\"s\"><v>1</v></c><c r=\"D1\" t=\"inlineStr\"><is><t>qty</t></is></c></row>"
                        + "<row r=\"3\"><c r=\"A3\"><v>1.50</v></c><c r=\"B3\" t=\"s\"><v>2</v></c>"
                        + "<c r=\"C3\" t=\"b\"><v>1</v></c><c r=\"D3\"><v>1E+3</v></c></row>");
        List<SheetRow> rows = XlsxReader.read(file);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).number()).isEqualTo(1);
        assertThat(rows.get(0).cells()).containsExactly("order_ref", "origin", "", "qty");
        assertThat(rows.get(1).number()).as("a row keeps the number the sheet gives it").isEqualTo(3);
        assertThat(rows.get(1).cells()).containsExactly("1.5", "Mumbai", "TRUE", "1000");
    }

    @Test
    @DisplayName("what is not a workbook is refused by name, never read as one")
    void notAWorkbook() {
        for (byte[] junk : List.of("plain text".getBytes(StandardCharsets.UTF_8), new byte[] {'P', 'K', 3, 4, 0, 0})) {
            assertThatThrownBy(() -> XlsxReader.read(junk)).isInstanceOfSatisfying(BusinessRuleViolationException.class,
                    e -> assertThat(e.code()).isEqualTo("intake-file-unreadable"));
        }
    }

    @Test
    @DisplayName("an external entity in a part is never resolved")
    void noExternalEntities() {
        byte[] file = workbook(null, "<row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>x</t></is></c></row>");
        assertThat(XlsxReader.read(file).get(0).cells()).containsExactly("x");
        String hostile = "<!DOCTYPE s [<!ENTITY e SYSTEM \"file:///etc/hostname\">]><sst>"
                + "<si><t>&e;</t></si></sst>";
        byte[] attack = workbook(hostile, "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c></row>");
        try {
            List<SheetRow> rows = XlsxReader.read(attack);
            assertThat(rows.get(0).cell(0)).as("the entity stays unexpanded").doesNotContain("/").hasSizeLessThan(2);
        } catch (BusinessRuleViolationException refused) {
            assertThat(refused.code()).isEqualTo("intake-file-unreadable");
        }
    }
}
