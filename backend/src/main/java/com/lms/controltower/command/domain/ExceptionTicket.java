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
package com.lms.controltower.command.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.lms.shared.error.BusinessRuleViolationException;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

/**
 * An operational anomaly on one trip, owned until somebody closes it (vision
 * document 3.8).
 *
 * <p>Immutable, like the other aggregates here: every transition returns the
 * next state and the service saves it, so a rule broken half-way through a
 * method cannot leave a half-updated ticket behind.
 */
@Table("exception_ticket")
public record ExceptionTicket(
        @Id UUID id,
        UUID tenantId,
        String ticketNo,
        UUID tripId,
        String tripNo,
        UUID vehicleId,
        ExceptionCategory category,
        Severity severity,
        Status status,
        String description,
        Source source,
        String sourceKey,
        BigDecimal lat,
        BigDecimal lon,
        PositionSource positionSource,
        Instant positionAt,
        boolean suspendsSettlement,
        String raisedBy,
        Instant raisedAt,
        UUID assigneeUserId,
        Instant assignedAt,
        String resolution,
        String resolvedBy,
        Instant resolvedAt,
        String closedBy,
        Instant closedAt,
        @Version Long version,
        Instant createdAt,
        Instant updatedAt) {

    /** Vision document 3.8, in order. */
    public enum Status {
        RAISED,
        /** Somebody owns it. */
        ASSIGNED,
        UNDER_INVESTIGATION,
        /** The owner believes it is dealt with. Not yet the manager's view. */
        RESOLVED,
        /** A manager agrees. Terminal, and what releases any settlement hold. */
        CLOSED
    }

    /** How the ticket came to exist. */
    public enum Source {
        /** A person reported it. */
        MANUAL,
        /** Telematics saw the vehicle leave its corridor. */
        ROUTE_DEVIATION
    }

    /** Where the captured coordinates came from. */
    public enum PositionSource {
        /** The vehicle's own last fix. */
        TELEMATICS,
        /** Supplied by whoever raised it -- usually the driver's phone. */
        REPORTED,
        /** Carried by the detection that raised the ticket. */
        DETECTOR,
        /** Nothing was available. Said outright rather than left blank. */
        NONE
    }

    /** A position and the moment it was true. */
    public record Position(BigDecimal lat, BigDecimal lon, PositionSource source, Instant at) {

        public static Position none() {
            return new Position(null, null, PositionSource.NONE, null);
        }
    }

    private static final Map<Status, Set<Status>> TRANSITIONS = new EnumMap<>(Status.class);

    static {
        TRANSITIONS.put(Status.RAISED, EnumSet.of(Status.ASSIGNED));
        // Reassignment keeps the status; it is a change of owner, not progress.
        TRANSITIONS.put(Status.ASSIGNED, EnumSet.of(Status.ASSIGNED, Status.UNDER_INVESTIGATION));
        TRANSITIONS.put(Status.UNDER_INVESTIGATION,
                EnumSet.of(Status.UNDER_INVESTIGATION, Status.RESOLVED));
        // A manager who does not accept the resolution sends it back rather
        // than closing it -- the alternative is closing tickets nobody believes.
        TRANSITIONS.put(Status.RESOLVED, EnumSet.of(Status.UNDER_INVESTIGATION, Status.CLOSED));
        TRANSITIONS.put(Status.CLOSED, EnumSet.noneOf(Status.class));
    }

    public static ExceptionTicket raise(UUID id, UUID tenantId, String ticketNo, UUID tripId,
                                        String tripNo, UUID vehicleId, ExceptionCategory category,
                                        String description, Source source, String sourceKey,
                                        Position position, String raisedBy, Instant at) {
        if (description == null || description.isBlank()) {
            throw new BusinessRuleViolationException("exception-not-described",
                    "An exception needs a description of what happened");
        }
        Severity severity = category.severity();
        return new ExceptionTicket(id, tenantId, ticketNo, tripId, tripNo, vehicleId, category,
                severity, Status.RAISED, description.strip(), source, sourceKey,
                position.lat(), position.lon(), position.source(), position.at(),
                severity.suspendsSettlement(), raisedBy, at,
                null, null, null, null, null, null, null,
                null, at, at);
    }

    public boolean isOpen() {
        return status != Status.CLOSED;
    }

    /** Gives the ticket an owner, or a new one. */
    public ExceptionTicket assignTo(UUID userId, Instant at) {
        if (userId == null) {
            throw new BusinessRuleViolationException("exception-assignee-required",
                    "Assigning ticket " + ticketNo + " needs somebody to assign it to");
        }
        Status target = status == Status.RAISED ? Status.ASSIGNED : status;
        if (status == Status.RESOLVED || status == Status.CLOSED) {
            refuse(Status.ASSIGNED);
        }
        requireTransition(target);
        return with(target, userId, at, resolution, resolvedBy, resolvedAt, closedBy, closedAt, at);
    }

    /**
     * Somebody is working it.
     *
     * <p>Only from ASSIGNED. Investigating an unowned ticket is how two people
     * end up chasing the same driver while a third ticket waits for anybody.
     */
    public ExceptionTicket startInvestigation(Instant at) {
        if (status != Status.ASSIGNED) {
            refuse(Status.UNDER_INVESTIGATION);
        }
        return with(Status.UNDER_INVESTIGATION, assigneeUserId, assignedAt, resolution,
                resolvedBy, resolvedAt, closedBy, closedAt, at);
    }

    /** The owner considers it dealt with, and says how. */
    public ExceptionTicket resolve(String how, String actor, Instant at) {
        if (how == null || how.isBlank()) {
            throw new BusinessRuleViolationException("resolution-not-explained",
                    "Resolving ticket " + ticketNo + " needs an account of how it was resolved");
        }
        if (status != Status.UNDER_INVESTIGATION) {
            refuse(Status.RESOLVED);
        }
        return with(Status.RESOLVED, assigneeUserId, assignedAt, how.strip(), actor, at,
                closedBy, closedAt, at);
    }

    /** The resolution was not accepted; back to whoever owns it. */
    public ExceptionTicket reopen(String why, Instant at) {
        if (why == null || why.isBlank()) {
            throw new BusinessRuleViolationException("reopen-not-explained",
                    "Reopening ticket " + ticketNo + " needs a reason the owner can act on");
        }
        if (status != Status.RESOLVED) {
            refuse(Status.UNDER_INVESTIGATION);
        }
        return with(Status.UNDER_INVESTIGATION, assigneeUserId, assignedAt, resolution,
                null, null, closedBy, closedAt, at);
    }

    /**
     * A manager formally closes it.
     *
     * <p>For a ticket that is holding up settlement, the manager must not be
     * the person who resolved it. Closing is what releases the money, and a
     * release one person can both justify and approve is not a control.
     */
    public ExceptionTicket close(String actor, Instant at) {
        if (status != Status.RESOLVED) {
            refuse(Status.CLOSED);
        }
        if (suspendsSettlement && actor != null && actor.equals(resolvedBy)) {
            throw new BusinessRuleViolationException("exception-close-needs-second-person",
                    "Ticket " + ticketNo + " is holding up settlement and was resolved by "
                            + actor + "; a different manager has to close it");
        }
        return with(Status.CLOSED, assigneeUserId, assignedAt, resolution, resolvedBy,
                resolvedAt, actor, at, at);
    }

    private void requireTransition(Status target) {
        if (!TRANSITIONS.getOrDefault(status, Set.of()).contains(target)) {
            refuse(target);
        }
    }

    private void refuse(Status target) {
        throw new BusinessRuleViolationException("exception-illegal-transition",
                "Ticket " + ticketNo + " cannot move from " + status + " to " + target);
    }

    private ExceptionTicket with(Status next, UUID assignee, Instant assigned, String resolutionText,
                                 String resolver, Instant resolved, String closer, Instant closed,
                                 Instant at) {
        return new ExceptionTicket(id, tenantId, ticketNo, tripId, tripNo, vehicleId, category,
                severity, next, description, source, sourceKey, lat, lon, positionSource,
                positionAt, suspendsSettlement, raisedBy, raisedAt, assignee, assigned,
                resolutionText, resolver, resolved, closer, closed, version, createdAt, at);
    }
}
