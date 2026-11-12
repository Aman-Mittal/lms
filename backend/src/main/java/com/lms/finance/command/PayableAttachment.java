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

package com.lms.finance.command;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.lms.finance.command.domain.FreightBill;
import com.lms.finance.command.domain.FreightBillLine;
import com.lms.finance.command.domain.ManualCharge;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.tenant.TenantContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Where an approved payable charge goes on the vendor's freight bill, and
 * nowhere else.
 *
 * <p>The rule, because a bill that has been judged is never moved: a charge is
 * added as a {@code MANUAL_CHARGE} line only to a bill that is still a DRAFT
 * (raised, never matched against the vendor's claim). A trip whose bill is
 * approved, disputed, cancelled or not raised yet leaves the charge waiting;
 * the next freight bill raised for that vendor takes it up as a line when it
 * is created. So an addition after approval is neither a re-priced approved
 * bill nor a bill of its own: it is a supplementary line on the vendor's next
 * bill, which is judged and paid as that bill is.
 */
@Component
class PayableAttachment {

    private final FreightBillRepository bills;
    private final FreightBillLineRepository lines;
    private final ManualChargeRepository charges;
    private final JdbcClient jdbc;

    PayableAttachment(FreightBillRepository bills, FreightBillLineRepository lines, ManualChargeRepository charges,
                      JdbcClient jdbc) {
        this.bills = bills;
        this.lines = lines;
        this.charges = charges;
        this.jdbc = jdbc;
    }

    /** Adds the charge to its trip's bill when that is an open draft; otherwise leaves it waiting. */
    @Transactional(propagation = Propagation.MANDATORY)
    public ManualCharge attachToTripBill(ManualCharge charge) {
        UUID tenantId = TenantContext.requireTenantId();
        Optional<FreightBill> bill = bills.findByTrip(tenantId, charge.tripId())
                .filter(b -> b.status() == FreightBill.BillStatus.DRAFT);
        return bill.map(b -> addLine(b, charge)).orElse(charge);
    }

    /**
     * Called when a freight bill has just been raised: takes up every payable
     * its vendor has waiting. Does nothing for a bill that is not a priced draft.
     *
     * @return the bill as it now stands, with the waiting charges' lines on it
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public FreightBill takeUpWaiting(FreightBill bill) {
        if (bill.status() != FreightBill.BillStatus.DRAFT || bill.vendorPartnerId() == null) {
            return bill;
        }
        FreightBill current = bill;
        for (ManualCharge waiting : charges.findWaiting(bill.tenantId(), bill.vendorPartnerId())) {
            current = bills.findById(current.id()).orElse(current);
            charges.save(addLine(current, waiting));
        }
        return bills.findById(bill.id()).orElse(bill);
    }

    /**
     * Removes the line of a cancelled charge from its bill, which must still be
     * a draft.
     *
     * @throws BusinessRuleViolationException {@code manual-charge-already-billed} otherwise
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ManualCharge removeFromBill(ManualCharge charge) {
        if (charge.freightBillId() == null) {
            return charge;
        }
        FreightBill bill = bills.findById(charge.freightBillId()).orElseThrow();
        if (bill.status() != FreightBill.BillStatus.DRAFT) {
            throw new BusinessRuleViolationException("manual-charge-already-billed",
                    "Charge " + charge.chargeNo() + " is a line of bill " + bill.billNo() + ", which is "
                            + bill.status() + "; it is cancelled only before its bill is judged");
        }
        jdbc.sql("DELETE FROM freight_bill_line WHERE tenant_id = :tenantId AND manual_charge_id = :chargeId")
                .param("tenantId", charge.tenantId())
                .param("chargeId", charge.id())
                .update();
        bills.save(bill.withManualAdjustment(charge.amount().negate()));
        return charge.detached();
    }

    private ManualCharge addLine(FreightBill bill, ManualCharge charge) {
        int next = nextLineNo(bill.id());
        lines.save(FreightBillLine.forManualCharge(UUID.randomUUID(), bill.tenantId(), bill.id(), next,
                "Manual charge " + charge.chargeCode() + " (" + charge.chargeNo() + "): " + charge.reason(),
                charge.quantity(), charge.basis().unit(), charge.rate(), charge.amount(), charge.id()));
        bills.save(bill.withManualAdjustment(charge.amount()));
        return charge.attachedTo(bill.id(), Instant.now());
    }

    private int nextLineNo(UUID billId) {
        List<FreightBillLine> existing = lines.findForBill(TenantContext.requireTenantId(), billId);
        return existing.stream().mapToInt(FreightBillLine::lineNo).max().orElse(0) + 1;
    }
}
