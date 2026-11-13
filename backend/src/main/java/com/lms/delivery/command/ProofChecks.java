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
package com.lms.delivery.command;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

import com.lms.delivery.command.domain.DeliveryProof;
import com.lms.delivery.command.domain.GoodsCondition;
import com.lms.shared.error.BusinessRuleViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * What a proof must satisfy to be submitted, whichever route submits it: the
 * lines fed (when the policy says they must be), the quantity that arrived
 * against the gross within tolerance, the front and back documents (when the
 * policy asks for them), and the goods' condition the lines imply.
 */
@Component
class ProofChecks {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final JdbcClient jdbc;
    private final PodPolicy policy;

    ProofChecks(JdbcClient jdbc, PodPolicy policy) {
        this.jdbc = jdbc;
        this.policy = policy;
    }

    /**
     * Refuses a proof that does not meet its policy, and says what condition
     * the goods are in: the worse of the one given and the one the lines show
     * (anything damaged is DAMAGE, anything short is SHORTAGE).
     *
     * @param given      the condition the submitter stated; null is CLEAN
     * @param legacyPhoto whether the one-step route carried a photo reference,
     *                   which then stands as the front document
     */
    GoodsCondition verify(DeliveryProof proof, GoodsCondition given, boolean legacyPhoto) {
        PodPolicy.Rules rules = policy.rulesFor(proof.consignmentId());
        Totals totals = totals(proof);

        if (rules.feedingRequired() && totals.fedLines == 0) {
            throw new BusinessRuleViolationException("pod-lines-required",
                    "This proof must be fed with what was received against its order lines before it is submitted");
        }
        if (totals.fedLines > 0 && totals.gross.signum() > 0) {
            BigDecimal allowed = totals.gross.multiply(rules.tolerancePercent())
                    .divide(HUNDRED, 3, RoundingMode.HALF_UP);
            BigDecimal deviation = totals.arrived.subtract(totals.gross).abs();
            if (deviation.compareTo(allowed) > 0) {
                throw new BusinessRuleViolationException("pod-quantity-outside-tolerance",
                        "The quantity that arrived is " + totals.arrived.stripTrailingZeros().toPlainString()
                                + " against a gross of " + totals.gross.stripTrailingZeros().toPlainString()
                                + ", which differs by more than the " + rules.tolerancePercent().stripTrailingZeros()
                                .toPlainString() + "% tolerance");
            }
        }
        boolean front = documentCount(proof.id(), "FRONT") > 0 || legacyPhoto;
        if (rules.frontRequired() && !front) {
            throw new BusinessRuleViolationException("pod-front-document-required",
                    "This proof must list a front document before it is submitted");
        }
        if (rules.backRequired() && documentCount(proof.id(), "BACK") == 0) {
            throw new BusinessRuleViolationException("pod-back-document-required",
                    "This proof must list a back document before it is submitted");
        }
        GoodsCondition shown = totals.damaged.signum() > 0 ? GoodsCondition.DAMAGE
                : totals.short_.signum() > 0 ? GoodsCondition.SHORTAGE : GoodsCondition.CLEAN;
        GoodsCondition stated = given == null ? GoodsCondition.CLEAN : given;
        return severity(shown) > severity(stated) ? shown : stated;
    }

    private static int severity(GoodsCondition condition) {
        return switch (condition) {
            case CLEAN -> 0;
            case SHORTAGE -> 1;
            case DAMAGE -> 2;
        };
    }

    private long documentCount(UUID proofId, String type) {
        return jdbc.sql("SELECT count(*) FROM delivery_proof_document WHERE proof_id = :p AND doc_type = :t")
                .param("p", proofId).param("t", type).query(Long.class).single();
    }

    /** Gross is every order line of the consignment; the rest is what was fed. */
    private record Totals(BigDecimal gross, BigDecimal arrived, BigDecimal short_, BigDecimal damaged,
                          int fedLines) {
    }

    private Totals totals(DeliveryProof proof) {
        BigDecimal gross = jdbc.sql("""
                        SELECT COALESCE(sum(ol.quantity), 0)
                          FROM consignment_line cl
                          JOIN order_line ol ON ol.id = cl.order_line_id
                         WHERE cl.consignment_id = :c
                        """)
                .param("c", proof.consignmentId()).query(BigDecimal.class).single();
        return jdbc.sql("""
                        SELECT COALESCE(sum(received_qty + damaged_qty + excess_qty), 0) AS arrived,
                               COALESCE(sum(short_qty), 0) AS short_qty,
                               COALESCE(sum(damaged_qty), 0) AS damaged_qty,
                               count(*) AS fed
                          FROM delivery_proof_line
                         WHERE proof_id = :p
                        """)
                .param("p", proof.id())
                .query((rs, row) -> new Totals(gross, rs.getBigDecimal("arrived"), rs.getBigDecimal("short_qty"),
                        rs.getBigDecimal("damaged_qty"), rs.getInt("fed")))
                .single();
    }
}
