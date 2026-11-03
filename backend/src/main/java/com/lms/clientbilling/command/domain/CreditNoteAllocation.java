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
package com.lms.clientbilling.command.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.lms.shared.error.BusinessRuleViolationException;

/**
 * Works out what a credit note credits, line by line, and the tax it reverses.
 *
 * <p>Pure, so the limits and the rounding can be pinned without a database.
 * Every credit-note line is against one invoice line, whichever way the credit
 * was asked for:
 *
 * <ul>
 *   <li><b>by line</b> -- named invoice lines, each for a stated taxable amount
 *       or, when none is stated, for everything that remains creditable on it;
 *   <li><b>by amount</b> -- a taxable amount spread over the invoice's lines in
 *       proportion to what remains creditable on each, to the cent by largest
 *       remainder, so the parts always add up to exactly the amount asked for.
 * </ul>
 *
 * <p>Tax is reversed at the line's own rate with the invoice rounding rule
 * ({@link Tax#on}). The one exception is the credit that exhausts a line: it
 * reverses exactly the tax still uncredited on that line, so a line credited
 * in several pieces never ends with a cent of tax charged or refunded twice.
 */
public final class CreditNoteAllocation {

    /** One invoice line, with what has already been credited against it. */
    public record Creditable(UUID invoiceLineId, int lineNo, ServiceType serviceType,
                             String narrative, BigDecimal taxable, BigDecimal taxRate,
                             BigDecimal tax, BigDecimal creditedTaxable,
                             BigDecimal creditedTax) {

        public BigDecimal remainingTaxable() {
            return taxable.subtract(creditedTaxable);
        }

        public BigDecimal remainingTax() {
            return tax.subtract(creditedTax);
        }
    }

    /** What one credit-note line takes off one invoice line. */
    public record Credit(Creditable line, BigDecimal taxable, BigDecimal tax) {
    }

    private static final BigDecimal CENT = new BigDecimal("0.01");

    private CreditNoteAllocation() {
    }

    /**
     * Credits named invoice lines.
     *
     * @param requested invoice line number to taxable amount; a null amount
     *                  credits whatever remains on that line
     */
    public static List<Credit> forLines(List<Creditable> lines,
                                        Map<Integer, BigDecimal> requested) {
        if (requested == null || requested.isEmpty()) {
            throw new IllegalArgumentException("A credit note by line must name at least one line");
        }
        Map<Integer, Creditable> byNo = new LinkedHashMap<>();
        lines.forEach(line -> byNo.put(line.lineNo(), line));

        List<Credit> credits = new ArrayList<>();
        requested.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    Creditable line = byNo.get(entry.getKey());
                    if (line == null) {
                        throw new BusinessRuleViolationException("client-credit-line-unknown",
                                "The invoice has no line " + entry.getKey());
                    }
                    BigDecimal remaining = line.remainingTaxable();
                    if (remaining.signum() <= 0) {
                        throw new BusinessRuleViolationException("client-credit-exceeds-invoice",
                                "Invoice line " + line.lineNo() + " has already been credited in full");
                    }
                    BigDecimal taxable = entry.getValue() == null ? remaining
                            : requireMoney(entry.getValue());
                    if (taxable.compareTo(remaining) > 0) {
                        throw new BusinessRuleViolationException("client-credit-exceeds-invoice",
                                "Invoice line " + line.lineNo() + " has " + remaining.toPlainString()
                                        + " left to credit; " + taxable.toPlainString()
                                        + " is more than that");
                    }
                    credits.add(new Credit(line, taxable, taxFor(line, taxable)));
                });
        return credits;
    }

    /**
     * Spreads a taxable amount over the invoice in proportion to what remains
     * creditable on each line.
     */
    public static List<Credit> forAmount(List<Creditable> lines, BigDecimal amount) {
        BigDecimal wanted = requireMoney(amount);
        BigDecimal remaining = lines.stream()
                .map(Creditable::remainingTaxable)
                .filter(r -> r.signum() > 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (wanted.compareTo(remaining) > 0) {
            throw new BusinessRuleViolationException("client-credit-exceeds-invoice",
                    "The invoice has " + remaining.toPlainString() + " left to credit; "
                            + wanted.toPlainString() + " is more than that");
        }

        List<Creditable> open = lines.stream()
                .filter(line -> line.remainingTaxable().signum() > 0)
                .sorted(Comparator.comparingInt(Creditable::lineNo))
                .toList();
        BigDecimal[] shares = new BigDecimal[open.size()];
        BigDecimal[] fractions = new BigDecimal[open.size()];
        BigDecimal allotted = BigDecimal.ZERO;
        for (int i = 0; i < open.size(); i++) {
            // wanted x remaining_i / remaining, cut to the cent; the cut-off
            // fraction decides who receives the cents left over.
            BigDecimal exact = wanted.multiply(open.get(i).remainingTaxable())
                    .divide(remaining, 12, RoundingMode.HALF_EVEN);
            shares[i] = exact.setScale(Tax.SCALE, RoundingMode.DOWN);
            fractions[i] = exact.subtract(shares[i]);
            allotted = allotted.add(shares[i]);
        }
        int leftoverCents = wanted.subtract(allotted).divide(CENT, 0, RoundingMode.UNNECESSARY)
                .intValueExact();
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < open.size(); i++) {
            order.add(i);
        }
        // Largest fraction first, then line order, so the result is
        // deterministic. A line whose share was exact has no fraction and so
        // is never pushed past what remains on it.
        order.sort(Comparator.<Integer, BigDecimal>comparing(i -> fractions[i]).reversed()
                .thenComparing(i -> open.get(i).lineNo()));
        for (int k = 0; k < leftoverCents; k++) {
            int i = order.get(k);
            shares[i] = shares[i].add(CENT);
        }

        List<Credit> credits = new ArrayList<>();
        for (int i = 0; i < open.size(); i++) {
            if (shares[i].signum() > 0) {
                credits.add(new Credit(open.get(i), shares[i], taxFor(open.get(i), shares[i])));
            }
        }
        return credits;
    }

    /**
     * The tax a credit of {@code taxable} reverses on one line: the line's rate
     * on the credited amount, except that the credit exhausting the line
     * reverses exactly the tax still uncredited on it.
     */
    public static BigDecimal taxFor(Creditable line, BigDecimal taxable) {
        if (taxable.compareTo(line.remainingTaxable()) == 0) {
            return line.remainingTax();
        }
        BigDecimal tax = Tax.on(taxable, line.taxRate());
        return tax.min(line.remainingTax());
    }

    /** Line numbers named more than once are a malformed request. */
    public static void requireDistinct(List<Integer> lineNos) {
        Set<Integer> seen = new HashSet<>();
        for (Integer lineNo : lineNos) {
            if (lineNo == null) {
                throw new IllegalArgumentException("Every credited line needs a line number");
            }
            if (!seen.add(lineNo)) {
                throw new IllegalArgumentException("Invoice line " + lineNo
                        + " is named twice in one credit note");
            }
        }
    }

    private static BigDecimal requireMoney(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("A credit must be for a positive amount");
        }
        if (amount.stripTrailingZeros().scale() > Tax.SCALE) {
            throw new IllegalArgumentException("A credit is money: at most two decimals, not "
                    + amount.toPlainString());
        }
        return amount.setScale(Tax.SCALE, RoundingMode.UNNECESSARY);
    }
}
