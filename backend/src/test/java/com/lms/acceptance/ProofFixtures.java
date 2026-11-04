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
package com.lms.acceptance;

import java.util.List;
import java.util.UUID;

import com.lms.delivery.command.DeliveryService;
import com.lms.delivery.command.domain.GoodsCondition;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Records proofs of delivery the way the delivery desk does: a code is issued
 * and then given back. The acceptance context exposes the code, as a demo
 * deployment does, so a step can read it from the issue.
 */
@Component
public class ProofFixtures {

    private final DeliveryService delivery;
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    public ProofFixtures(DeliveryService delivery, JdbcClient jdbc,
                         PlatformTransactionManager transactionManager) {
        this.delivery = delivery;
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** The full-truckload consignments the trip's load carries, live ones only. */
    public List<UUID> consignmentsOnTrip(UUID tripId) {
        return transactions.execute(status -> jdbc.sql("""
                        SELECT c.id
                          FROM trip t
                          JOIN load_consignment lc ON lc.load_id = t.load_id
                          JOIN consignment c ON c.id = lc.consignment_id
                         WHERE t.id = :tripId
                           AND c.freight_mode <> 'LTL'
                           AND c.status NOT IN ('CANCELLED', 'SPLIT')
                         ORDER BY c.tracking_ref
                        """)
                .param("tripId", tripId)
                .query(UUID.class)
                .list());
    }

    /** The trip a step names, by the salted number every step family raises it under. */
    public UUID tripNamed(String saltedTripNo) {
        return transactions.execute(status -> jdbc.sql("SELECT id FROM trip WHERE trip_no = :no")
                .param("no", saltedTripNo)
                .query(UUID.class)
                .single());
    }

    /** Issues a code and gives it back, recording the goods as received. */
    public void prove(UUID consignmentId, GoodsCondition condition, String remark) {
        String otp = delivery.issueOtp(consignmentId).otp();
        if (otp == null) {
            throw new IllegalStateException("The acceptance context must expose delivery codes");
        }
        delivery.recordProof(consignmentId, otp, "Receiving clerk", null, null, null, null,
                condition, remark);
    }

    /** Proves every consignment the trip carried. */
    public void proveTrip(UUID tripId, GoodsCondition condition, String remark) {
        consignmentsOnTrip(tripId).forEach(id -> prove(id, condition, remark));
    }
}
