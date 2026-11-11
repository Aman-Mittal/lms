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

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.lms.shared.tenant.TenantContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Finance's record of which trips the control tower has frozen (vision
 * document 3.8).
 *
 * <p>Keyed on the ticket, so two critical tickets on one trip are two holds
 * and closing the first leaves the second in force.
 *
 * <p>Both writes are upserts that commute. The outbox delivers at least once
 * and, after a restart, in no promised order, so a close can arrive before
 * its raise. Placing never clears a release that is already recorded, and
 * releasing creates the row if the placement has not arrived yet -- whichever
 * order the two land in, the result is a released hold, never a hold that
 * nothing will ever lift.
 *
 * <p>A second kind of hold waits for proofs of delivery: a trip whose
 * consignments are not all proved clean when it completes is held until they
 * are, or until somebody with {@code SETTLEMENT_HOLD_RELEASE} releases it and
 * says why. At most one per trip, keyed on an id derived from the trip, so a
 * redelivered completion places nothing new, and a hold released by hand is
 * never placed again. Only a trip that delivers to consignees is held: a
 * part-load line haul ends at a hub, and its dockets are proved on their
 * delivery leg.
 */
@Component
public class SettlementHolds {

    private final JdbcClient jdbc;

    public SettlementHolds(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void place(UUID exceptionId, UUID tripId, String ticketNo, Instant placedAt) {
        jdbc.sql("""
                        INSERT INTO settlement_hold (tenant_id, exception_id, trip_id, ticket_no,
                                                     placed_at, reason)
                        VALUES (:tenantId, :exceptionId, :tripId, :ticketNo, :placedAt, 'EXCEPTION')
                        ON CONFLICT (tenant_id, exception_id) DO NOTHING
                        """)
                .param("tenantId", TenantContext.requireTenantId())
                .param("exceptionId", exceptionId)
                .param("tripId", tripId)
                .param("ticketNo", ticketNo)
                .param("placedAt", placedAt.atOffset(ZoneOffset.UTC))
                .update();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(UUID exceptionId, UUID tripId, String ticketNo, Instant placedAt,
                        Instant releasedAt) {
        jdbc.sql("""
                        INSERT INTO settlement_hold (tenant_id, exception_id, trip_id, ticket_no,
                                                     placed_at, released_at)
                        VALUES (:tenantId, :exceptionId, :tripId, :ticketNo, :placedAt,
                                :releasedAt)
                        ON CONFLICT (tenant_id, exception_id)
                        DO UPDATE SET released_at = coalesce(settlement_hold.released_at,
                                                             EXCLUDED.released_at)
                        """)
                .param("tenantId", TenantContext.requireTenantId())
                .param("exceptionId", exceptionId)
                .param("tripId", tripId)
                .param("ticketNo", ticketNo)
                .param("placedAt", placedAt.atOffset(ZoneOffset.UTC))
                .param("releasedAt", releasedAt.atOffset(ZoneOffset.UTC))
                .update();
    }

    /**
     * The exception tickets still holding this trip, oldest first; empty when
     * no ticket holds it. A proof hold is asked about with {@link #awaitingProof}.
     */
    @Transactional(readOnly = true)
    public List<String> holdingTickets(UUID tripId) {
        return jdbc.sql("""
                        SELECT ticket_no FROM settlement_hold
                         WHERE tenant_id = :tenantId AND trip_id = :tripId
                           AND released_at IS NULL AND reason = 'EXCEPTION'
                         ORDER BY placed_at
                        """)
                .param("tenantId", TenantContext.requireTenantId())
                .param("tripId", tripId)
                .query(String.class)
                .list();
    }

    // ----------------------------------------------------- proof-of-delivery holds

    /** What a proof hold is filed under in the ticket column. */
    static final String PROOF_HOLD_TICKET = "PROOF-OF-DELIVERY";

    /** The one id a trip's proof hold is keyed on. */
    static UUID proofHoldId(UUID tripId) {
        return UUID.nameUUIDFromBytes(("proof-of-delivery:" + tripId).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Holds a completed trip's settlement if any consignment it delivered lacks
     * a clean confirmed proof. Nothing happens for a trip already clean, a line
     * haul, or a trip whose proof hold was placed (or released) before.
     *
     * @return whether the trip is held for proofs after this call
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean holdForProofs(UUID tripId, Instant completedAt) {
        // Proofs are read only once the trip is ours: a proof that committed
        // while we waited is seen, and one committing later re-asks after us.
        lockTrip(tripId);
        if (!lacksCleanProof(tripId)) {
            return awaitingProofNow(tripId);
        }
        jdbc.sql("""
                        INSERT INTO settlement_hold (tenant_id, exception_id, trip_id, ticket_no,
                                                     placed_at, reason)
                        VALUES (:tenantId, :holdId, :tripId, :ticketNo, :placedAt, 'PROOF_OF_DELIVERY')
                        ON CONFLICT (tenant_id, exception_id) DO NOTHING
                        """)
                .param("tenantId", TenantContext.requireTenantId())
                .param("holdId", proofHoldId(tripId))
                .param("tripId", tripId)
                .param("ticketNo", PROOF_HOLD_TICKET)
                .param("placedAt", completedAt.atOffset(ZoneOffset.UTC))
                .update();
        return awaitingProofNow(tripId);
    }

    /**
     * Releases the proof hold of every trip that carried this consignment and
     * is now proved clean throughout. A trip still waiting on another
     * consignment, or on a proof that recorded a shortage or damage, stays held.
     *
     * <p>Every trip that carried the consignment is locked and asked again, not
     * only those already held: a completion still deciding about the trip may
     * be about to place a hold. The lock serialises the two, so whichever runs
     * second sees what the first committed -- a hold placed first is released
     * here, and a proof committed first is seen by the completion -- and a hold
     * never outlives clean proofs. Trips are locked in id order, so two proofs
     * on one load cannot deadlock.
     *
     * @return the trips released by this call
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<UUID> releaseWhereProved(UUID consignmentId, Instant at) {
        List<UUID> carrying = jdbc.sql("""
                        SELECT DISTINCT t.id
                          FROM trip t
                          JOIN load_consignment lc ON lc.load_id = t.load_id
                         WHERE t.tenant_id = :tenantId AND lc.consignment_id = :consignmentId
                           AND t.status <> 'CANCELLED'
                         ORDER BY t.id
                        """)
                .param("tenantId", TenantContext.requireTenantId())
                .param("consignmentId", consignmentId)
                .query(UUID.class)
                .list();
        List<UUID> released = new java.util.ArrayList<>();
        for (UUID tripId : carrying) {
            lockTrip(tripId);
            if (awaitingProofNow(tripId) && !lacksCleanProof(tripId)
                    && release(tripId, at, null, null) > 0) {
                released.add(tripId);
            }
        }
        return released;
    }

    /**
     * A reversed proof puts back the hold it lifted.
     *
     * <p>Every completed trip that carried the consignment and now lacks a
     * clean proof is held again: a hold the proof released by itself is
     * reopened, and one that was never placed (the proof came before the
     * completion) is placed now. A hold a person released by hand stays
     * released -- that person accepted the trip on other evidence. Under the
     * same per-trip lock as every other decision about the hold, and safe to
     * repeat: a hold already open is left as it is.
     *
     * @return the trips held by this call
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<UUID> reholdWhereReversed(UUID consignmentId, Instant at) {
        UUID tenantId = TenantContext.requireTenantId();
        List<UUID> completed = jdbc.sql("""
                        SELECT DISTINCT t.id
                          FROM trip t
                          JOIN load_consignment lc ON lc.load_id = t.load_id
                         WHERE t.tenant_id = :tenantId AND lc.consignment_id = :consignmentId
                           AND t.status = 'COMPLETED'
                         ORDER BY t.id
                        """)
                .param("tenantId", tenantId)
                .param("consignmentId", consignmentId)
                .query(UUID.class)
                .list();
        List<UUID> held = new java.util.ArrayList<>();
        for (UUID tripId : completed) {
            lockTrip(tripId);
            if (awaitingProofNow(tripId) || !lacksCleanProof(tripId)) {
                continue;
            }
            int reopened = jdbc.sql("""
                            UPDATE settlement_hold
                               SET released_at = NULL
                             WHERE tenant_id = :tenantId AND exception_id = :holdId
                               AND released_at IS NOT NULL AND released_by IS NULL AND release_note IS NULL
                            """)
                    .param("tenantId", tenantId)
                    .param("holdId", proofHoldId(tripId))
                    .update();
            int placed = reopened > 0 ? 0 : jdbc.sql("""
                            INSERT INTO settlement_hold (tenant_id, exception_id, trip_id, ticket_no,
                                                         placed_at, reason)
                            VALUES (:tenantId, :holdId, :tripId, :ticketNo, :placedAt, 'PROOF_OF_DELIVERY')
                            ON CONFLICT (tenant_id, exception_id) DO NOTHING
                            """)
                    .param("tenantId", tenantId)
                    .param("holdId", proofHoldId(tripId))
                    .param("tripId", tripId)
                    .param("ticketNo", PROOF_HOLD_TICKET)
                    .param("placedAt", at.atOffset(ZoneOffset.UTC))
                    .update();
            if (reopened + placed > 0) {
                held.add(tripId);
            }
        }
        return held;
    }

    /**
     * Serialises every decision about one trip's proof hold until the
     * transaction ends. Package-private so a test can stand in for a
     * completion that is still deciding.
     */
    void lockTrip(UUID tripId) {
        jdbc.sql("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))) held")
                .param("key", "settlement-proof:" + TenantContext.requireTenantId() + ":" + tripId)
                .query(Integer.class)
                .single();
    }

    /**
     * Releases a trip's proof hold by hand, in the caller's transaction, so the
     * release and its audit entry commit together.
     *
     * @return whether a hold was open and is now released
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean releaseByHand(UUID tripId, Instant at, UUID releasedBy, String note) {
        return release(tripId, at, releasedBy, note) > 0;
    }

    /** Whether the trip's proof hold is open. */
    @Transactional(readOnly = true)
    public boolean awaitingProof(UUID tripId) {
        return awaitingProofNow(tripId);
    }

    private boolean awaitingProofNow(UUID tripId) {
        return jdbc.sql("""
                        SELECT count(*) FROM settlement_hold
                         WHERE tenant_id = :tenantId AND trip_id = :tripId
                           AND released_at IS NULL AND reason = 'PROOF_OF_DELIVERY'
                        """)
                .param("tenantId", TenantContext.requireTenantId())
                .param("tripId", tripId)
                .query(Long.class)
                .single() > 0;
    }

    private int release(UUID tripId, Instant at, UUID releasedBy, String note) {
        return jdbc.sql("""
                        UPDATE settlement_hold
                           SET released_at = :at, released_by = :by, release_note = :note
                         WHERE tenant_id = :tenantId AND exception_id = :holdId
                           AND released_at IS NULL
                        """)
                .param("tenantId", TenantContext.requireTenantId())
                .param("holdId", proofHoldId(tripId))
                .param("at", at.atOffset(ZoneOffset.UTC))
                .param("by", releasedBy)
                .param("note", note)
                .update();
    }

    /**
     * Whether the trip delivered to consignees and any live consignment on its
     * load lacks a confirmed proof whose goods were received clean.
     */
    private boolean lacksCleanProof(UUID tripId) {
        return Boolean.TRUE.equals(jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1
                              FROM trip t
                              JOIN load_unit l ON l.id = t.load_id
                              JOIN load_consignment lc ON lc.load_id = t.load_id
                              JOIN consignment c ON c.id = lc.consignment_id
                             WHERE t.tenant_id = :tenantId AND t.id = :tripId
                               AND (l.freight_mode <> 'LTL' OR l.ltl_leg = 'DELIVERY')
                               AND c.status NOT IN ('CANCELLED', 'SPLIT')
                               AND NOT EXISTS (
                                   SELECT 1 FROM delivery_proof dp
                                    WHERE dp.tenant_id = c.tenant_id AND dp.consignment_id = c.id
                                      AND dp.status = 'CONFIRMED' AND dp.goods_condition = 'CLEAN'))
                        """)
                .param("tenantId", TenantContext.requireTenantId())
                .param("tripId", tripId)
                .query(Boolean.class)
                .single());
    }
}
