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

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.lms.settings.api.SettingContext;
import com.lms.settings.api.Settings;
import com.lms.shared.error.BusinessRuleViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Order files (FR-ORD-013): a CSV or XLSX parsed into orders and fed to
 * {@link BulkIntakeService}, so a file goes through exactly the path a batch
 * does. The columns are the customer's own (the {@code order.intake.columnMapping}
 * setting, over a default); terminals and consignees are named by code.
 *
 * <p>One row is one order line; rows that share an order reference make one
 * order. A row that cannot be read fails with its number and every reason
 * found in it, and so does every other row of its order: an order is made
 * whole or not at all. Rows of other orders are unaffected. The key of an
 * order is the batch key (given, else a digest of the file) with the customer
 * and the reference, so uploading the same file again answers DUPLICATE row by
 * row and creates nothing.
 */
@Service
public class IntakeFileService {

    public static final int MAX_FILE_BYTES = 2 * 1024 * 1024;
    public static final int MAX_ROWS = 2_000;

    private static final DateTimeFormatter[] DATE_FORMATS = {
        DateTimeFormatter.ISO_LOCAL_DATE, DateTimeFormatter.ofPattern("d/M/uuuu"),
        DateTimeFormatter.ofPattern("d-M-uuuu"), DateTimeFormatter.ofPattern("d.M.uuuu")};
    private static final LocalDate EXCEL_EPOCH = LocalDate.of(1899, 12, 30);

    private final BulkIntakeService bulk;
    private final Settings settings;
    private final JdbcClient jdbc;
    private final TransactionTemplate readOnly;

    public IntakeFileService(BulkIntakeService bulk, Settings settings, JdbcClient jdbc,
                             PlatformTransactionManager manager) {
        this.bulk = bulk;
        this.settings = settings;
        this.jdbc = jdbc;
        this.readOnly = new TransactionTemplate(manager);
        this.readOnly.setReadOnly(true);
    }

    /** One row's fate: its number in the file, the order it belongs to and what happened. */
    public record RowResult(int row, String orderRef, BulkResult.Status status, UUID orderId, List<String> reasons) {
    }

    /** The counts are of orders (an order of several rows counts once), the rows are every data row read. */
    public record Outcome(int created, int duplicates, int failed, List<RowResult> rows) {
    }

    @PreAuthorize("hasAuthority('ORDER_CREATE') and hasAuthority('ORDER_UPDATE')"
            + " and @abac.permitsIn('ORDER_CREATE', #orgUnitId)")
    public Outcome upload(UUID orgUnitId, UUID customerPartnerId, String filename, byte[] content, String batchKey) {
        if (content == null || content.length == 0) {
            throw new BusinessRuleViolationException("intake-file-empty", "The file is empty");
        }
        if (content.length > MAX_FILE_BYTES) {
            throw new BusinessRuleViolationException("intake-file-too-large",
                    "A file is at most " + MAX_FILE_BYTES / (1024 * 1024) + " MB");
        }
        List<SheetRow> table = parse(filename, content);
        List<SheetRow> rows = table.stream().filter(r -> !r.blank()).toList();
        if (rows.size() < 2) {
            throw new BusinessRuleViolationException("intake-file-empty", "The file has a header and no rows");
        }
        if (rows.size() - 1 > MAX_ROWS) {
            throw new BusinessRuleViolationException("intake-too-many-rows",
                    "A file holds at most " + MAX_ROWS + " rows; this one has " + (rows.size() - 1));
        }

        Map<String, Object> mapping = settings.effective(IntakeSettingDefinitions.COLUMN_MAPPING,
                SettingContext.ofOrgUnit(orgUnitId).withCustomer(customerPartnerId));
        IntakeColumns columns = IntakeColumns.of(rows.get(0), mapping);
        List<SheetRow> data = rows.subList(1, rows.size());
        Codes codes = lookUp(columns, data);

        String prefix = (batchKey == null || batchKey.isBlank() ? digestOf(content) : batchKey.strip())
                + ":" + customerPartnerId + ":";
        Map<String, Group> groups = new LinkedHashMap<>();
        Map<Integer, List<String>> rowErrors = new HashMap<>();
        for (SheetRow row : data) {
            String ref = columns.value(row, IntakeField.ORDER_REF);
            List<String> errors = new ArrayList<>();
            BulkItem.Line line = lineOf(columns, row, codes, errors);
            UUID origin = codes.terminals.get(columns.value(row, IntakeField.ORIGIN).toUpperCase(Locale.ROOT));
            if (origin == null) {
                errors.add("origin terminal \"" + columns.value(row, IntakeField.ORIGIN) + "\" is not a terminal code");
            }
            Instant pickup = dateOf(columns.value(row, IntakeField.PICKUP_DATE), "pickup date", errors);
            Instant delivery = dateOf(columns.value(row, IntakeField.DELIVERY_DATE), "delivery date", errors);
            if (ref.isEmpty()) {
                errors.add("the order reference is blank");
            }
            Group group = groups.computeIfAbsent(ref.isEmpty() ? "#row" + row.number() : ref,
                    k -> new Group(ref));
            group.rows.add(row.number());
            if (!errors.isEmpty()) {
                rowErrors.put(row.number(), errors);
                group.failed = true;
                continue;
            }
            group.merge(row.number(), origin, pickup, delivery,
                    columns.value(row, IntakeField.LOAD_TYPE), line, rowErrors);
        }

        return run(orgUnitId, customerPartnerId, prefix, groups, rowErrors);
    }

    private Outcome run(UUID orgUnitId, UUID customerPartnerId, String prefix, Map<String, Group> groups,
                        Map<Integer, List<String>> rowErrors) {
        List<Group> runnable = groups.values().stream().filter(g -> !g.failed && !rowErrorsOf(g, rowErrors)).toList();
        List<BulkItem> items = runnable.stream().map(g -> new BulkItem(prefix + g.ref, customerPartnerId, null,
                g.origin, g.pickup, g.delivery, g.loadType, g.lines)).toList();
        List<BulkResult> results = items.isEmpty() ? List.of() : bulk.submit(orgUnitId, items);

        Map<Integer, RowResult> byRow = new HashMap<>();
        int created = 0;
        int duplicates = 0;
        int failed = 0;
        for (int i = 0; i < runnable.size(); i++) {
            BulkResult result = results.get(i);
            switch (result.status()) {
                case CREATED -> created++;
                case DUPLICATE -> duplicates++;
                case FAILED -> failed++;
                default -> {
                }
            }
            for (int row : runnable.get(i).rows) {
                byRow.put(row, new RowResult(row, runnable.get(i).ref, result.status(), result.orderId(),
                        result.reasons()));
            }
        }
        for (Group group : groups.values()) {
            if (runnable.contains(group)) {
                continue;
            }
            failed++;
            for (int row : group.rows) {
                List<String> reasons = rowErrors.getOrDefault(row, List.of("another row of order \"" + group.ref
                        + "\" failed, so the order was not made"));
                byRow.put(row, new RowResult(row, group.ref, BulkResult.Status.FAILED, null, reasons));
            }
        }
        List<RowResult> rows = byRow.values().stream().sorted(java.util.Comparator.comparingInt(RowResult::row))
                .toList();
        return new Outcome(created, duplicates, failed, rows);
    }

    private static boolean rowErrorsOf(Group group, Map<Integer, List<String>> rowErrors) {
        return group.rows.stream().anyMatch(rowErrors::containsKey);
    }

    // ------------------------------------------------------------------ rows

    /** The rows of one order, as they accumulate. */
    private static final class Group {
        final String ref;
        final List<Integer> rows = new ArrayList<>();
        final List<BulkItem.Line> lines = new ArrayList<>();
        boolean failed;
        UUID origin;
        Instant pickup;
        Instant delivery;
        String loadType;

        Group(String ref) {
            this.ref = ref;
        }

        /** Adds a row; rows of one order share their origin and load type, and the first dates stated stand. */
        void merge(int rowNumber, UUID rowOrigin, Instant rowPickup, Instant rowDelivery, String rowLoadType,
                   BulkItem.Line line, Map<Integer, List<String>> rowErrors) {
            if (origin == null) {
                origin = rowOrigin;
            } else if (!origin.equals(rowOrigin)) {
                rowErrors.put(rowNumber, List.of("the origin differs from the first row of order \"" + ref
                        + "\"; an order has one origin"));
                return;
            }
            if (!rowLoadType.isEmpty()) {
                if (loadType == null) {
                    loadType = rowLoadType;
                } else if (!loadType.equalsIgnoreCase(rowLoadType)) {
                    rowErrors.put(rowNumber, List.of("the load type differs from the first row of order \"" + ref
                            + "\""));
                    return;
                }
            }
            pickup = pickup == null ? rowPickup : pickup;
            delivery = delivery == null ? rowDelivery : delivery;
            lines.add(line);
        }
    }

    private static BulkItem.Line lineOf(IntakeColumns columns, SheetRow row, Codes codes, List<String> errors) {
        UUID destination = codes.terminals.get(columns.value(row, IntakeField.DESTINATION).toUpperCase(Locale.ROOT));
        if (destination == null) {
            errors.add("destination terminal \"" + columns.value(row, IntakeField.DESTINATION)
                    + "\" is not a terminal code");
        }
        String consigneeCode = columns.value(row, IntakeField.CONSIGNEE);
        UUID consignee = consigneeCode.isEmpty() ? null : codes.partners.get(consigneeCode.toUpperCase(Locale.ROOT));
        if (!consigneeCode.isEmpty() && consignee == null) {
            errors.add("consignee \"" + consigneeCode + "\" is not a partner code");
        }
        BigDecimal quantity = numberOf(columns.value(row, IntakeField.QUANTITY), "quantity", errors);
        if (quantity != null && quantity.signum() <= 0) {
            errors.add("the quantity is above zero");
        }
        String unit = columns.value(row, IntakeField.UOM);
        String uom = unit.isEmpty() ? "KG" : unit;
        BigDecimal weight = numberOf(columns.value(row, IntakeField.WEIGHT_KG), "weight", errors);
        if (weight == null && quantity != null && Set.of("KG", "KGS").contains(uom.toUpperCase(Locale.ROOT))) {
            weight = quantity;
        }
        if (weight == null && quantity != null && errors.stream().noneMatch(e -> e.startsWith("the weight"))) {
            errors.add("the weight is needed when the unit is not kilograms");
        }
        String code = columns.value(row, IntakeField.MATERIAL_CODE);
        String material = columns.value(row, IntakeField.MATERIAL);
        return new BulkItem.Line(code.isEmpty() ? "GENERAL" : code, material.isEmpty() ? "Goods" : material, null,
                null, quantity, uom, weight, consignee, destination);
    }

    private static BigDecimal numberOf(String text, String what, List<String> errors) {
        if (text.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(text.replace(" ", ""));
        } catch (NumberFormatException e) {
            errors.add("the " + what + " \"" + text + "\" is not a number");
            return null;
        }
    }

    /** A date as ISO or day-first text, or as the serial number Excel stores; blank is none. */
    static Instant dateOf(String text, String what, List<String> errors) {
        if (text.isEmpty()) {
            return null;
        }
        if (text.matches("\\d{5}(\\.\\d+)?")) {
            long serial = new BigDecimal(text).longValue();
            if (serial > 20_000 && serial < 80_000) {
                return EXCEL_EPOCH.plusDays(serial).atStartOfDay(ZoneOffset.UTC).toInstant();
            }
        }
        for (DateTimeFormatter format : DATE_FORMATS) {
            try {
                return LocalDate.parse(text, format).atStartOfDay(ZoneOffset.UTC).toInstant();
            } catch (DateTimeParseException tryNext) {
                // the next format
            }
        }
        errors.add("the " + what + " \"" + text + "\" is not a date (use yyyy-mm-dd)");
        return null;
    }

    // ------------------------------------------------------------------ lookups

    private record Codes(Map<String, UUID> terminals, Map<String, UUID> partners) {
    }

    /** The terminals and partners the file names by code, in two statements; codes compare ignoring case. */
    private Codes lookUp(IntakeColumns columns, List<SheetRow> data) {
        Set<String> terminalCodes = new java.util.HashSet<>();
        Set<String> partnerCodes = new java.util.HashSet<>();
        for (SheetRow row : data) {
            terminalCodes.add(columns.value(row, IntakeField.ORIGIN).toUpperCase(Locale.ROOT));
            terminalCodes.add(columns.value(row, IntakeField.DESTINATION).toUpperCase(Locale.ROOT));
            partnerCodes.add(columns.value(row, IntakeField.CONSIGNEE).toUpperCase(Locale.ROOT));
        }
        terminalCodes.remove("");
        partnerCodes.remove("");
        return readOnly.execute(status -> new Codes(idsByCode("terminal", terminalCodes),
                idsByCode("business_partner", partnerCodes)));
    }

    private Map<String, UUID> idsByCode(String table, Set<String> upperCodes) {
        Map<String, UUID> ids = new HashMap<>();
        if (upperCodes.isEmpty()) {
            return ids;
        }
        jdbc.sql("SELECT id, code FROM " + table + " WHERE tenant_id = app_current_tenant()"
                        + " AND upper(code) IN (:codes)")
                .param("codes", upperCodes).query((rs, n) -> {
                    ids.putIfAbsent(rs.getString("code").toUpperCase(Locale.ROOT), rs.getObject("id", UUID.class));
                    return null;
                }).list();
        return ids;
    }

    // ------------------------------------------------------------------ parsing

    private static List<SheetRow> parse(String filename, byte[] content) {
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        boolean zip = content.length > 3 && content[0] == 'P' && content[1] == 'K';
        if (name.endsWith(".xlsx") || (zip && !name.endsWith(".csv"))) {
            return XlsxReader.read(content);
        }
        if (name.endsWith(".csv") || name.endsWith(".txt") || name.isEmpty()) {
            return CsvReader.read(new String(content, StandardCharsets.UTF_8));
        }
        throw new BusinessRuleViolationException("intake-file-type-unsupported",
                "Upload a .csv or .xlsx file");
    }

    private static String digestOf(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)).substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every JDK", e);
        }
    }
}
