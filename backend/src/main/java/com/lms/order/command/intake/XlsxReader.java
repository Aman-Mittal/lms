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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import com.lms.shared.error.BusinessRuleViolationException;

/**
 * Reads the first worksheet of an .xlsx workbook with nothing but the JDK: a
 * workbook is a zip of XML parts, and the cells wanted here are text and
 * numbers. Cheaper in memory and in image size than a spreadsheet library,
 * which matters on this deployment; the price is that formulas are read as the
 * value Excel last stored, and dates arrive as the serial number Excel keeps
 * (the file importer turns one into a date where a date is expected).
 *
 * <p>Defensive about what it is given: no DTD or external entity is ever
 * resolved, a part is read only up to {@link #MAX_PART_BYTES}, and a zip with
 * more than {@link #MAX_ENTRIES} parts is refused, so a hostile archive cannot
 * exhaust memory.
 */
final class XlsxReader {

    static final int MAX_PART_BYTES = 16 * 1024 * 1024;
    static final int MAX_ENTRIES = 200;

    private static final String NOT_A_WORKBOOK = "intake-file-unreadable";

    private XlsxReader() {
    }

    static List<SheetRow> read(byte[] file) {
        Map<String, byte[]> parts = parts(file);
        try {
            List<String> shared = parts.containsKey("xl/sharedStrings.xml")
                    ? sharedStrings(parts.get("xl/sharedStrings.xml")) : List.of();
            byte[] sheet = parts.get(firstSheetPath(parts));
            if (sheet == null) {
                throw unreadable("the workbook has no worksheet");
            }
            return sheetRows(sheet, shared);
        } catch (XMLStreamException e) {
            throw unreadable("the workbook is not well-formed");
        }
    }

    private static Map<String, byte[]> parts(byte[] file) {
        Map<String, byte[]> parts = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(file))) {
            ZipEntry entry;
            int count = 0;
            while ((entry = zip.getNextEntry()) != null) {
                if (++count > MAX_ENTRIES) {
                    throw unreadable("the archive holds too many parts");
                }
                String name = entry.getName();
                if (name.equals("xl/sharedStrings.xml") || name.equals("xl/workbook.xml")
                        || name.equals("xl/_rels/workbook.xml.rels") || name.startsWith("xl/worksheets/sheet")) {
                    parts.put(name, readBounded(zip));
                }
            }
        } catch (IOException e) {
            throw unreadable("the file is not a workbook");
        }
        if (!parts.containsKey("xl/workbook.xml")) {
            throw unreadable("the file is not a workbook");
        }
        return parts;
    }

    private static byte[] readBounded(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) {
            out.write(buffer, 0, n);
            if (out.size() > MAX_PART_BYTES) {
                throw unreadable("a part of the workbook is too large");
            }
        }
        return out.toByteArray();
    }

    /** The part of the first sheet in the workbook's order, through its relationship; sheet1 when that fails. */
    private static String firstSheetPath(Map<String, byte[]> parts) {
        try {
            String relId = null;
            XMLStreamReader wb = reader(parts.get("xl/workbook.xml"));
            while (wb.hasNext() && relId == null) {
                if (wb.next() == XMLStreamConstants.START_ELEMENT && wb.getLocalName().equals("sheet")) {
                    for (int i = 0; i < wb.getAttributeCount(); i++) {
                        if (wb.getAttributeLocalName(i).equals("id")) {
                            relId = wb.getAttributeValue(i);
                        }
                    }
                }
            }
            byte[] rels = parts.get("xl/_rels/workbook.xml.rels");
            if (relId != null && rels != null) {
                XMLStreamReader r = reader(rels);
                while (r.hasNext()) {
                    if (r.next() == XMLStreamConstants.START_ELEMENT && r.getLocalName().equals("Relationship")
                            && relId.equals(r.getAttributeValue(null, "Id"))) {
                        String target = r.getAttributeValue(null, "Target");
                        return target.startsWith("/") ? target.substring(1) : "xl/" + target;
                    }
                }
            }
        } catch (XMLStreamException e) {
            // fall through to the conventional name
        }
        return "xl/worksheets/sheet1.xml";
    }

    private static List<String> sharedStrings(byte[] xml) throws XMLStreamException {
        List<String> strings = new ArrayList<>();
        XMLStreamReader r = reader(xml);
        StringBuilder current = null;
        while (r.hasNext()) {
            int event = r.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                if (r.getLocalName().equals("si")) {
                    current = new StringBuilder();
                } else if (r.getLocalName().equals("t") && current != null) {
                    current.append(r.getElementText());
                }
            } else if (event == XMLStreamConstants.END_ELEMENT && r.getLocalName().equals("si") && current != null) {
                strings.add(current.toString());
                current = null;
            }
        }
        return strings;
    }

    private static List<SheetRow> sheetRows(byte[] xml, List<String> shared) throws XMLStreamException {
        List<SheetRow> rows = new ArrayList<>();
        XMLStreamReader r = reader(xml);
        int rowNumber = 0;
        List<String> cells = null;
        while (r.hasNext()) {
            int event = r.next();
            if (event == XMLStreamConstants.START_ELEMENT && r.getLocalName().equals("row")) {
                String declared = r.getAttributeValue(null, "r");
                rowNumber = declared == null ? rowNumber + 1 : Integer.parseInt(declared);
                cells = new ArrayList<>();
            } else if (event == XMLStreamConstants.START_ELEMENT && r.getLocalName().equals("c") && cells != null) {
                int column = columnOf(r.getAttributeValue(null, "r"), cells.size());
                String type = r.getAttributeValue(null, "t");
                String value = cellValue(r, type, shared);
                while (cells.size() < column) {
                    cells.add("");
                }
                cells.add(value);
            } else if (event == XMLStreamConstants.END_ELEMENT && r.getLocalName().equals("row") && cells != null) {
                rows.add(new SheetRow(rowNumber, cells));
                cells = null;
            }
        }
        return rows;
    }

    /** The text of the cell the reader is on, read to the cell's end. */
    private static String cellValue(XMLStreamReader r, String type, List<String> shared) throws XMLStreamException {
        String value = "";
        StringBuilder inline = new StringBuilder();
        while (r.hasNext()) {
            int event = r.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                if (r.getLocalName().equals("v")) {
                    value = r.getElementText();
                } else if (r.getLocalName().equals("t")) {
                    inline.append(r.getElementText());
                }
            } else if (event == XMLStreamConstants.END_ELEMENT && r.getLocalName().equals("c")) {
                break;
            }
        }
        if (type == null) {
            return number(value);
        }
        return switch (type) {
            case "s" -> {
                int index = value.isBlank() ? -1 : Integer.parseInt(value.strip());
                yield index >= 0 && index < shared.size() ? shared.get(index) : "";
            }
            case "inlineStr" -> inline.toString();
            case "b" -> "1".equals(value) ? "TRUE" : "FALSE";
            case "n" -> number(value);
            default -> value;
        };
    }

    /** A stored number as plain text: no exponent, no trailing zeros. */
    private static String number(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        try {
            BigDecimal n = new BigDecimal(raw.strip());
            return n.signum() == 0 ? "0" : n.stripTrailingZeros().toPlainString();
        } catch (NumberFormatException e) {
            return raw;
        }
    }

    /** The zero-based column of a reference such as {@code C7}; the next free one when it is absent. */
    private static int columnOf(String reference, int fallback) {
        if (reference == null) {
            return fallback;
        }
        int column = 0;
        for (int i = 0; i < reference.length() && Character.isLetter(reference.charAt(i)); i++) {
            column = column * 26 + (Character.toUpperCase(reference.charAt(i)) - 'A' + 1);
        }
        return column == 0 ? fallback : column - 1;
    }

    private static XMLStreamReader reader(byte[] xml) throws XMLStreamException {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        return factory.createXMLStreamReader(new ByteArrayInputStream(xml));
    }

    private static BusinessRuleViolationException unreadable(String why) {
        return new BusinessRuleViolationException(NOT_A_WORKBOOK, "The file could not be read: " + why);
    }
}
