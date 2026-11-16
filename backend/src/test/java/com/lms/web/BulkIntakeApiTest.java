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
package com.lms.web;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.lms.order.command.intake.IntakeSettingDefinitions;
import com.lms.settings.api.Settings;
import com.lms.shared.tenant.TenantContext;
import com.lms.web.ApiTestKit.Outcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bulk order intake over HTTP with real permissions: per-item results where
 * one bad item never fails another, an idempotency key per item (a repeat is a
 * DUPLICATE, other content under the key is refused, a race makes one order),
 * the batch cap, and CSV and XLSX files read through a customer's column
 * mapping into row-level results.
 */
class BulkIntakeApiTest extends OrderIntakeApiBase {

    @Autowired
    private Settings settings;

    // ------------------------------------------------------------- helpers

    private String lineJson(UUID to, String quantity) {
        return "{\"materialCode\":\"MAT\",\"materialDescription\":\"Goods\",\"quantity\":" + quantity
                + ",\"uom\":\"EA\",\"deadWeightKg\":100,\"destinationTerminalId\":\"" + to + "\"}";
    }

    private String item(String key, String... lines) {
        return "{" + (key == null ? "" : "\"idempotencyKey\":\"" + key + "\",") + "\"customerPartnerId\":\"" + client
                + "\",\"originTerminalId\":\"" + origin + "\",\"lines\":[" + String.join(",", lines) + "]}";
    }

    private String batch(String... items) {
        return "{\"orgUnitId\":\"" + orgUnitId + "\",\"items\":[" + String.join(",", items) + "]}";
    }

    private JsonNode submit(String body) {
        return ok(call("POST", "/api/v1/order-intake/batches", body, null), 200);
    }

    private long orders() {
        return jdbc.sql("SELECT count(*) FROM sales_order WHERE tenant_id = :t").param("t", tenantId)
                .query(Long.class).single();
    }

    private Outcome upload(ApiTestKit.Tenant who, String filename, byte[] content, Map<String, String> fields,
                           String... permissions) {
        String boundary = "----intake" + UUID.randomUUID();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        fields.forEach((name, value) -> text(out, "--" + boundary + "\r\nContent-Disposition: form-data; name=\""
                + name + "\"\r\n\r\n" + value + "\r\n"));
        text(out, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename
                + "\"\r\nContent-Type: application/octet-stream\r\n\r\n");
        out.writeBytes(content);
        text(out, "\r\n--" + boundary + "--\r\n");
        return kit.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/order-intake/files"))
                .header("Authorization", "Bearer " + kit.token(who, permissions))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build());
    }

    private static void text(ByteArrayOutputStream out, String value) {
        out.writeBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private Outcome upload(String filename, String csv) {
        return upload(caller, filename, csv.getBytes(StandardCharsets.UTF_8), fields(), DESK);
    }

    private Map<String, String> fields() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("orgUnitId", orgUnitId.toString());
        fields.put("customerPartnerId", client.toString());
        return fields;
    }

    private String originCode() {
        return "ORG-" + salt;
    }

    private String destinationCode() {
        return "DST-" + salt;
    }

    // --------------------------------------------------------------- batch

    @Test
    @DisplayName("each item is raised and validated on its own: one bad item never fails or rolls back the others")
    void oneBadItemNeverFailsTheOthers() {
        JsonNode result = submit(batch(
                item("k-good-1", lineJson(destination, "2")),
                item("k-bad-terminal", lineJson(UUID.randomUUID(), "2")),
                item("k-no-lines"),
                item("k-good-2", lineJson(destination, "1"), lineJson(elsewhere, "4"))));

        assertThat(result.get("created").asInt()).isEqualTo(2);
        assertThat(result.get("failed").asInt()).isEqualTo(2);
        assertThat(result.get("duplicates").asInt()).isZero();
        JsonNode results = result.get("results");
        assertThat(results).hasSize(4);
        for (int i = 0; i < 4; i++) {
            assertThat(results.get(i).get("index").asInt()).isEqualTo(i);
        }
        assertThat(results.get(0).get("status").asString()).isEqualTo("CREATED");
        assertThat(results.get(0).get("orderId").asString()).isNotBlank();
        assertThat(results.get(0).get("reasons")).isEmpty();
        assertThat(results.get(1).get("status").asString()).isEqualTo("FAILED");
        assertThat(absent(results.get(1), "orderId")).isTrue();
        assertThat(results.get(1).get("reasons").get(0).asString()).contains("Terminal");
        assertThat(results.get(2).get("status").asString()).isEqualTo("FAILED");
        assertThat(results.get(2).get("reasons").get(0).asString()).startsWith("order-has-no-lines");
        assertThat(results.get(3).get("status").asString()).isEqualTo("CREATED");

        assertThat(orders()).as("a failed item left nothing behind").isEqualTo(2);
        assertThat(jdbc.sql("SELECT DISTINCT source || '/' || status FROM sales_order WHERE tenant_id = :t")
                .param("t", tenantId).query(String.class).list()).containsExactly("BULK_UPLOAD/VALIDATED");
        UUID second = UUID.fromString(results.get(3).get("orderId").asString());
        assertThat(ok(call("GET", "/api/v1/orders/" + second + "/lines", null, null), 200)).hasSize(2);
    }

    @Test
    @DisplayName("an item sent again is a duplicate naming its order, a key reused for other content fails, a missing key fails")
    void idempotencyPerItem() {
        String body = batch(item("k-1", lineJson(destination, "2")), item("k-2", lineJson(destination, "3")));
        JsonNode first = submit(body);
        JsonNode again = submit(body);

        assertThat(again.get("created").asInt()).isZero();
        assertThat(again.get("duplicates").asInt()).isEqualTo(2);
        for (int i = 0; i < 2; i++) {
            assertThat(again.get("results").get(i).get("status").asString()).isEqualTo("DUPLICATE");
            assertThat(again.get("results").get(i).get("orderId").asString())
                    .isEqualTo(first.get("results").get(i).get("orderId").asString());
        }
        assertThat(orders()).isEqualTo(2);

        JsonNode mixed = submit(batch(item("k-1", lineJson(destination, "9")), item(null, lineJson(destination, "1")),
                item("k-3", lineJson(destination, "1"))));
        assertThat(mixed.get("results").get(0).get("status").asString()).isEqualTo("FAILED");
        assertThat(mixed.get("results").get(0).get("reasons").get(0).asString()).startsWith("idempotency-key-reused");
        assertThat(mixed.get("results").get(1).get("reasons").get(0).asString())
                .startsWith("idempotency-key-required");
        assertThat(mixed.get("results").get(2).get("status").asString()).as("the good item still goes in")
                .isEqualTo("CREATED");
        assertThat(orders()).isEqualTo(3);
    }

    @Test
    @DisplayName("the same key sent at once from six requests makes exactly one order")
    void sameKeyRace() {
        String body = batch(item("k-race", lineJson(destination, "2")));
        List<Callable<Outcome>> calls = java.util.stream.IntStream.range(0, 6)
                .<Callable<Outcome>>mapToObj(i -> () -> call("POST", "/api/v1/order-intake/batches", body, null))
                .toList();
        List<Outcome> results = ApiTestKit.race(calls, Duration.ofSeconds(90));

        assertThat(results).extracting(Outcome::status).containsOnly(200);
        List<String> statuses = results.stream().map(o -> kit.parse(o).get("results").get(0).get("status").asString())
                .toList();
        assertThat(statuses.stream().filter("CREATED"::equals)).hasSize(1);
        assertThat(statuses.stream().filter("DUPLICATE"::equals)).hasSize(5);
        assertThat(orders()).isEqualTo(1);
    }

    @Test
    @DisplayName("a batch over 500 items is refused whole with its own code, an empty one is a bad request, a missing right is 403")
    void batchLimits() {
        String bare = "{\"customerPartnerId\":\"" + client + "\",\"originTerminalId\":\"" + origin + "\"}";
        refused(call("POST", "/api/v1/order-intake/batches",
                batch(java.util.Collections.nCopies(501, bare).toArray(String[]::new)), null), 409,
                "bulk-batch-too-large");
        refused(call("POST", "/api/v1/order-intake/batches", batch(), null), 400, null);
        refused(call(caller, "POST", "/api/v1/order-intake/batches", batch(item("k", lineJson(destination, "1"))),
                null, "ORDER_CREATE"), 403, null);
        assertThat(orders()).isZero();
    }

    // ---------------------------------------------------------------- files

    private static final String HEADER = "order_ref,origin,destination,pickup_date,quantity,uom,weight_kg,material,"
            + "consignee,load_type\n";

    private String sampleCsv() {
        String o = originCode();
        String d = destinationCode();
        return HEADER
                + "A1," + o + "," + d + ",2026-12-01,10,EA,500,Steel," + "CONS-" + salt + ",\n"
                + "A1," + o + "," + d + ",2026-12-01,5,EA,250,Bolts,,\n"
                + "B1," + o + ",NOWHERE,2026-12-01,1,EA,10,Chairs,,\n"
                + "C1," + o + "," + d + ",01/12/2026,2,KG,,Sand,,\n"
                + "D1," + o + "," + d + ",,abc,EA,10,Bad,,\n";
    }

    @Test
    @DisplayName("a CSV becomes orders by order reference, every row is answered with its number, and the same file again creates nothing")
    void csvUpload() {
        JsonNode first = ok(upload("orders.csv", sampleCsv()), 200);

        assertThat(first.get("created").asInt()).isEqualTo(2);
        assertThat(first.get("failed").asInt()).isEqualTo(2);
        assertThat(first.get("duplicates").asInt()).isZero();
        JsonNode rows = first.get("rows");
        assertThat(rows).hasSize(5);
        assertThat(rows.get(0).get("row").asInt()).as("the header is row 1").isEqualTo(2);
        assertThat(rows.get(1).get("row").asInt()).isEqualTo(3);
        assertThat(rows.get(0).get("orderRef").asString()).isEqualTo("A1");
        assertThat(rows.get(0).get("status").asString()).isEqualTo("CREATED");
        assertThat(rows.get(1).get("orderId").asString()).as("rows of one reference make one order")
                .isEqualTo(rows.get(0).get("orderId").asString());
        assertThat(rows.get(2).get("row").asInt()).isEqualTo(4);
        assertThat(rows.get(2).get("status").asString()).isEqualTo("FAILED");
        assertThat(rows.get(2).get("reasons").get(0).asString()).contains("NOWHERE");
        assertThat(rows.get(3).get("status").asString()).as("kilograms stand in for the weight").isEqualTo("CREATED");
        assertThat(rows.get(4).get("row").asInt()).isEqualTo(6);
        assertThat(rows.get(4).get("reasons").get(0).asString()).contains("abc");

        UUID a1 = UUID.fromString(rows.get(0).get("orderId").asString());
        assertThat(ok(call("GET", "/api/v1/orders/" + a1 + "/lines", null, null), 200)).hasSize(2);
        assertThat(orders()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT DISTINCT source FROM sales_order WHERE tenant_id = :t").param("t", tenantId)
                .query(String.class).list()).containsExactly("BULK_UPLOAD");

        JsonNode again = ok(upload("orders.csv", sampleCsv()), 200);
        assertThat(again.get("created").asInt()).isZero();
        assertThat(again.get("duplicates").asInt()).isEqualTo(2);
        assertThat(again.get("failed").asInt()).isEqualTo(2);
        assertThat(again.get("rows").get(0).get("status").asString()).isEqualTo("DUPLICATE");
        assertThat(again.get("rows").get(0).get("orderId").asString()).isEqualTo(a1.toString());
        assertThat(orders()).isEqualTo(2);
    }

    @Test
    @DisplayName("one row that cannot be read fails its whole order and no other, and rows of an order must agree on its origin")
    void rowErrorsFailTheirOrder() {
        String o = originCode();
        String d = destinationCode();
        JsonNode result = ok(upload("orders.csv", HEADER
                + "X1," + o + "," + d + ",,1,EA,10,Good,,\n"
                + "X1," + o + ",MISSING,,1,EA,10,Bad,,\n"
                + "Y1," + o + "," + d + ",,1,EA,10,Fine,,\n"
                + "Z1," + o + "," + d + ",,1,EA,10,Fine,,\n"
                + "Z1,OTH-" + salt + "," + d + ",,1,EA,10,Elsewhere,,\n"), 200);

        assertThat(result.get("created").asInt()).isEqualTo(1);
        assertThat(result.get("failed").asInt()).isEqualTo(2);
        JsonNode rows = result.get("rows");
        assertThat(rows.get(0).get("status").asString()).isEqualTo("FAILED");
        assertThat(rows.get(0).get("reasons").get(0).asString()).contains("another row of order \"X1\"");
        assertThat(rows.get(1).get("reasons").get(0).asString()).contains("MISSING");
        assertThat(rows.get(2).get("status").asString()).isEqualTo("CREATED");
        assertThat(rows.get(3).get("status").asString()).isEqualTo("FAILED");
        assertThat(rows.get(4).get("reasons").get(0).asString()).contains("one origin");
        assertThat(orders()).isEqualTo(1);
    }

    @Test
    @DisplayName("a customer's own column names and a semicolon delimiter are read through the column mapping setting")
    void columnMapping() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("setup", "n/a",
                java.util.Set.of(new SimpleGrantedAuthority("SETTINGS_MANAGE"))));
        try {
            TenantContext.callWith(tenantId, orgPath, () -> new TransactionTemplate(transactionManager).execute(s -> {
                settings.setOrgDefaults(Map.of(IntakeSettingDefinitions.COLUMN_MAPPING,
                        Map.of("orderRef", "Ref no", "origin", "Pick-up point", "quantity", "Qty")));
                return null;
            }));
        } finally {
            SecurityContextHolder.clearContext();
        }
        String csv = "Ref no;Pick-up point;Destination;Qty;UOM\n"
                + "M1;" + originCode() + ";" + destinationCode() + ";7;KG\n";
        JsonNode result = ok(upload("mine.csv", csv), 200);
        assertThat(result.get("created").asInt()).isEqualTo(1);
        assertThat(result.get("rows").get(0).get("status").asString()).isEqualTo("CREATED");

        refused(upload("default.csv", "order_ref,origin,destination,quantity\nM2," + originCode() + ","
                + destinationCode() + ",1\n"), 409, "intake-columns-missing");
    }

    @Test
    @DisplayName("an XLSX is read like a CSV: text and number cells, an Excel date, the first worksheet")
    void xlsxUpload() {
        String[][] rows = {
            {"order_ref", "origin", "destination", "pickup_date", "quantity", "uom"},
            {"W1", originCode(), destinationCode(), "#46357", "#12", "KG"},
            {"W2", originCode(), "NOWHERE", "", "#1", "KG"}};
        Outcome outcome = upload(caller, "orders.xlsx", workbook(rows), fields(), DESK);
        JsonNode result = ok(outcome, 200);

        assertThat(result.get("created").asInt()).isEqualTo(1);
        assertThat(result.get("failed").asInt()).isEqualTo(1);
        assertThat(result.get("rows").get(0).get("row").asInt()).isEqualTo(2);
        assertThat(result.get("rows").get(0).get("status").asString()).isEqualTo("CREATED");
        assertThat(result.get("rows").get(1).get("reasons").get(0).asString()).contains("NOWHERE");
        assertThat(jdbc.sql("SELECT requested_pickup_at::date::text FROM sales_order WHERE tenant_id = :t")
                .param("t", tenantId).query(String.class).single()).isEqualTo("2026-12-01");
    }

    @Test
    @DisplayName("a file is refused by name when empty, too big, too long, of another type or missing a column, and without the right")
    void fileLimits() {
        refused(upload("orders.csv", ""), 409, "intake-file-empty");
        refused(upload("orders.csv", HEADER), 409, "intake-file-empty");
        refused(upload("orders.pdf", HEADER + "A,B,C,,1,EA,1,x,,\n"), 409, "intake-file-type-unsupported");
        refused(upload("orders.csv", "order_ref,origin\nA,B\n"), 409, "intake-columns-missing");

        byte[] huge = new byte[2 * 1024 * 1024 + 1];
        java.util.Arrays.fill(huge, (byte) 'a');
        refused(upload(caller, "orders.csv", huge, fields(), DESK), 409, "intake-file-too-large");

        StringBuilder rows = new StringBuilder(HEADER);
        for (int i = 0; i < 2001; i++) {
            rows.append("R").append(i).append(",a,b,,1,EA,1,x,,\n");
        }
        refused(upload("orders.csv", rows.toString()), 409, "intake-too-many-rows");

        StringBuilder orders = new StringBuilder(HEADER);
        for (int i = 0; i < 501; i++) {
            orders.append("R").append(i).append(',').append(originCode()).append(',').append(destinationCode())
                    .append(",,1,EA,1,x,,\n");
        }
        refused(upload("orders.csv", orders.toString()), 409, "bulk-batch-too-large");

        refused(upload(caller, "orders.csv", sampleCsv().getBytes(StandardCharsets.UTF_8), fields(), "ORDER_CREATE"),
                403, null);
        assertThat(orders()).isZero();
    }

    // ----------------------------------------------------------- workbooks

    /** A one-sheet workbook; a cell starting with {@code #} is a number, the rest are inline text. */
    private static byte[] workbook(String[][] rows) {
        StringBuilder sheet = new StringBuilder();
        for (int r = 0; r < rows.length; r++) {
            sheet.append("<row r=\"").append(r + 1).append("\">");
            for (int c = 0; c < rows[r].length; c++) {
                String cell = rows[r][c];
                if (cell.isEmpty()) {
                    continue;
                }
                String ref = String.valueOf((char) ('A' + c)) + (r + 1);
                if (cell.startsWith("#")) {
                    sheet.append("<c r=\"").append(ref).append("\"><v>").append(cell.substring(1)).append("</v></c>");
                } else {
                    sheet.append("<c r=\"").append(ref).append("\" t=\"inlineStr\"><is><t>").append(cell)
                            .append("</t></is></c>");
                }
            }
            sheet.append("</row>");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            part(zip, "xl/workbook.xml", "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\""
                    + " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                    + "<sheets><sheet name=\"Orders\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
            part(zip, "xl/_rels/workbook.xml.rels",
                    "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                            + "<Relationship Id=\"rId1\" Type=\"worksheet\" Target=\"worksheets/sheet1.xml\"/>"
                            + "</Relationships>");
            part(zip, "xl/worksheets/sheet1.xml", "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                    + "<sheetData>" + sheet + "</sheetData></worksheet>");
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
        return out.toByteArray();
    }

    private static void part(ZipOutputStream zip, String name, String content) throws java.io.IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
