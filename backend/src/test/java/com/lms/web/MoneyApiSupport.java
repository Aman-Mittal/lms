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

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.lms.clientbilling.command.ClientContractService;
import com.lms.clientbilling.command.domain.ClientContract;
import com.lms.finance.command.BillingService;
import com.lms.finance.command.FreightBillLineRepository;
import com.lms.finance.command.FreightBillRepository;
import com.lms.finance.command.TariffCommandService;
import com.lms.finance.command.domain.CostSheet;
import com.lms.finance.command.domain.FreightBill;
import com.lms.finance.command.domain.FreightBillLine;
import com.lms.finance.command.domain.TariffSlab;
import com.lms.web.ApiTestKit.Outcome;
import com.lms.web.ApiTestKit.Tenant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

import com.lms.shared.tenant.TenantContext;

/**
 * What the money API tests share (issue #225): the yard's tenant, a lorry and a
 * trip, plus the calls to build a catalogue entry, an approved freight bill and
 * a client contract, and to decide an approval request as a second person.
 */
abstract class MoneyApiSupport extends YardApiSupport {

    static final String CATALOGUE = "CHARGE_CATALOGUE_MANAGE";
    static final String RECORD = "MANUAL_CHARGE_RECORD";
    static final String PAYABLES = "PAYABLES_MANAGE";
    static final String ADVANCE = "ADVANCE_REQUEST";

    @Autowired
    protected FreightBillRepository bills;
    @Autowired
    protected FreightBillLineRepository billLines;
    @Autowired
    protected BillingService billing;
    @Autowired
    protected TariffCommandService tariffs;
    @Autowired
    protected ClientContractService contracts;

    /** A second person of the yard's tenant, who decides what the first asked for. */
    protected Tenant approver() {
        return kit.tenantOf(yard.tenantId(), yard.orgUnitId(), yard.tenant().orgPath());
    }

    // ------------------------------------------------------------- catalogue

    protected UUID entry(String code, String basis, String rate, String direction) {
        Outcome made = post("/api/v1/charge-catalogue", json("code", q(code), "name", q("Charge " + code), "basis",
                q(basis), "defaultRate", rate, "direction", q(direction)), CATALOGUE);
        if (made.status() != 201) {
            throw new AssertionError("Catalogue entry refused: " + made.status() + " " + made.body());
        }
        return UUID.fromString(id(made));
    }

    protected Outcome charge(UUID entry, String subjectType, UUID subject, String quantity, String rate,
                             String direction, String... permissions) {
        List<String> pairs = new ArrayList<>(List.of("catalogueId", q(entry), "subjectType", q(subjectType),
                "subjectId", q(subject), "quantity", quantity, "reason", q("Waited at the gate")));
        if (rate != null) {
            pairs.addAll(List.of("rate", rate));
        }
        if (direction != null) {
            pairs.addAll(List.of("direction", q(direction)));
        }
        return post("/api/v1/manual-charges", json(pairs.toArray(String[]::new)),
                permissions.length == 0 ? new String[] {RECORD} : permissions);
    }

    protected JsonNode chargeView(String id) {
        Outcome got = get("/api/v1/manual-charges/" + id, RECORD);
        if (got.status() != 200) {
            throw new AssertionError("Charge not readable: " + got.status() + " " + got.body());
        }
        return kit.parse(got);
    }

    // ---------------------------------------------------------- approvals

    /** Approves the request as a second person holding the right the policy names. */
    protected void approve(JsonNode ticket, String permission) {
        String request = ticket.get("id").asString();
        Tenant decider = approver();
        Outcome detail = kit.get(decider, "/api/v1/approvals/" + request, "APPROVAL_READ");
        String version = kit.parse(detail).get("approval").get("version").asString();
        Outcome decided = kit.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/approvals/"
                        + request + "/approve"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + kit.token(decider, permission, "APPROVAL_READ"))
                .header("If-Match", "\"" + version + "\"")
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build());
        if (decided.status() != 204) {
            throw new AssertionError("Approval refused: " + decided.status() + " " + decided.body());
        }
    }

    // ------------------------------------------------------------ freight bills

    /** A priced draft bill of the yard's vendor for the trip, with one base-freight line of {@code amount}. */
    protected UUID draftBill(UUID tripId, String amount) {
        BigDecimal total = new BigDecimal(amount);
        return as(List.of(), () -> new TransactionTemplate(transactionManager).execute(status -> {
            UUID load = jdbc.sql("SELECT load_id FROM trip WHERE id = :id").param("id", tripId).query(UUID.class)
                    .single();
            CostSheet sheet = new CostSheet("INR", new BigDecimal("5000"), BigDecimal.ZERO, 1, total,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    List.of(new CostSheet.Charge(FreightBillLine.ChargeType.BASE_FREIGHT, "Base freight",
                            BigDecimal.ONE, "run", total, total)));
            FreightBill bill = bills.save(FreightBill.priced(UUID.randomUUID(), yard.tenantId(), yard.orgUnitId(),
                    tripId, load, yard.vendor(), "FB-" + UUID.randomUUID().toString().substring(0, 8), null,
                    LocalDate.now(), sheet));
            billLines.save(FreightBillLine.of(UUID.randomUUID(), yard.tenantId(), bill.id(), 1,
                    FreightBillLine.ChargeType.BASE_FREIGHT, "Base freight", BigDecimal.ONE, "run", total, total));
            return bill.id();
        }));
    }

    /** A bill matched with the claim equal to its amount, so it is approved for payment. */
    protected UUID approvedBill(UUID tripId, String amount) {
        UUID bill = draftBill(tripId, amount);
        approveBill(bill, amount);
        return bill;
    }

    protected void approveBill(UUID bill, String claimed) {
        Outcome matched = post("/api/v1/freight-bills/" + bill + "/match", json("claimedAmount", claimed),
                "INVOICE_APPROVE");
        if (matched.status() != 200 || !matched.body().contains("APPROVED_FOR_PAYMENT")) {
            throw new AssertionError("Bill not approved: " + matched.status() + " " + matched.body());
        }
    }

    protected JsonNode billView(UUID bill) {
        return kit.parse(get("/api/v1/freight-bills/" + bill, "INVOICE_READ"));
    }

    protected String billNo(UUID bill) {
        return billView(bill).get("billNo").asString();
    }

    // -------------------------------------------------------------- contracts

    /** A client contract for the yard's customer, in force since three months ago. */
    protected UUID clientContract() {
        return as(List.of("CLIENT_CONTRACT_MANAGE"), () -> contracts.publishContract(yard.orgUnitId(),
                "CC-" + UUID.randomUUID().toString().substring(0, 6), yard.customer(), "INR",
                LocalDate.now().minusMonths(3),
                new ClientContract.Rates(new BigDecimal("2"), new BigDecimal("100"), BigDecimal.ONE, BigDecimal.ONE,
                        BigDecimal.ONE, new BigDecimal("18"), new BigDecimal("18"), null),
                List.of(), false));
    }

    /** A rate card for the yard's vendor on the yard's lane, at {@code perKg} a kilo. */
    protected UUID rateCard(String perKg) {
        return as(List.of("TARIFF_MANAGE"), () -> {
            UUID card = tariffs.publishTariff(yard.orgUnitId(), "RC-" + UUID.randomUUID().toString().substring(0, 6),
                    yard.vendor(), yard.origin(), yard.dest(), "RIGID", "INR", LocalDate.now().minusDays(30),
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
            tariffs.addSlab(card, BigDecimal.ZERO, null, TariffSlab.RateBasis.PER_KG, new BigDecimal(perKg));
            return card;
        });
    }

    // ------------------------------------------------------------------ plumbing

    /** Runs {@code action} as the yard's tenant holding {@code permissions} (the setup ones too). */
    protected <T> T as(List<String> permissions, Supplier<T> action) {
        Set<SimpleGrantedAuthority> authorities = new java.util.HashSet<>(
                List.of("PARTNER_CREATE", "TRIP_EXECUTE").stream().map(SimpleGrantedAuthority::new)
                        .collect(Collectors.toSet()));
        permissions.forEach(p -> authorities.add(new SimpleGrantedAuthority(p)));
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("money-setup", null, authorities));
        try {
            return TenantContext.callWith(yard.tenantId(), yard.tenant().orgPath(), action);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    /** A POST carrying an {@code Idempotency-Key}. */
    protected Outcome postKeyed(String path, String body, String key, String... permissions) {
        return kit.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", key)
                .header("Authorization", "Bearer " + kit.token(yard.tenant(), permissions))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build());
    }

    protected long audited(String action, String resourceId) {
        return jdbc.sql("SELECT count(*) FROM audit_log WHERE action = :a AND resource_id = CAST(:r AS uuid)")
                .param("a", action).param("r", resourceId).query(Long.class).single();
    }

    protected static BigDecimal dec(JsonNode node, String field) {
        return node.get(field).decimalValue();
    }
}
