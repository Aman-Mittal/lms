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
package com.lms.controltower.command;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import com.lms.controltower.command.domain.ExceptionActivity;
import com.lms.controltower.command.domain.ExceptionCategory;
import com.lms.controltower.command.domain.ExceptionTicket;
import com.lms.controltower.events.ExceptionClosed;
import com.lms.controltower.events.ExceptionRaised;
import com.lms.execution.api.TripLookupPort;
import com.lms.shared.audit.AuditTrail;
import com.lms.shared.config.Json;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.tenant.TenantContext;
import com.lms.telematics.api.VehicleFixPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Raises and works exception tickets (vision document 3.8).
 *
 * <p>Permissions follow who is accountable for each step. Raising is open to
 * anybody who can see a problem; working a ticket is the control tower desk's;
 * closing -- and refusing a resolution -- belongs to whoever holds
 * {@code EXCEPTION_CLOSE}, because closing a critical ticket is what releases
 * a vendor's money.
 */
@Service
public class ExceptionCommandService {

    /** Who an automatic raise is attributed to. */
    static final String SYSTEM_ACTOR = "system";

    private final ExceptionTicketRepository tickets;
    private final ExceptionActivityRepository activities;
    private final TripLookupPort trips;
    private final VehicleFixPort fixes;
    private final AuditTrail audit;
    private final ApplicationEventPublisher events;

    /**
     * How old a telematics fix may be and still count as where the vehicle is.
     *
     * <p>Thirty minutes by default. Older than that, a position the reporter
     * supplies -- the driver standing next to the lorry -- is the better
     * answer to "where did this happen". A stale fix is still kept when it is
     * all there is, with its own timestamp, so the ticket never claims to know
     * more than it does.
     */
    private final Duration maxFixAge;

    public ExceptionCommandService(ExceptionTicketRepository tickets,
                                   ExceptionActivityRepository activities,
                                   TripLookupPort trips, VehicleFixPort fixes,
                                   AuditTrail audit, ApplicationEventPublisher events,
                                   @Value("${lms.controltower.max-fix-age-minutes:30}")
                                   long maxFixAgeMinutes) {
        this.tickets = tickets;
        this.activities = activities;
        this.trips = trips;
        this.fixes = fixes;
        this.audit = audit;
        this.events = events;
        this.maxFixAge = Duration.ofMinutes(maxFixAgeMinutes);
    }

    /**
     * A person reports an exception on a trip.
     *
     * @param reportedLat optional; where the reporter says it happened, used
     *                    when the vehicle has no recent fix of its own
     */
    @Transactional
    @PreAuthorize("hasAuthority('EXCEPTION_RAISE')")
    public UUID raise(UUID tripId, ExceptionCategory category, String description,
                      BigDecimal reportedLat, BigDecimal reportedLon) {
        UUID tenantId = TenantContext.requireTenantId();
        TripLookupPort.TripSnapshot trip = requireRaisableTrip(tripId);
        Instant now = Instant.now();

        ExceptionTicket.Position position =
                capturePosition(trip.vehicleId(), reportedLat, reportedLon, now);

        return open(tenantId, trip, null, category, description, ExceptionTicket.Source.MANUAL,
                null, position, currentActor(), now);
    }

    /**
     * Turns a route deviation into a P2 ticket.
     *
     * <p>Unguarded: the actor is the platform, reacting to telematics. And
     * idempotent on the detection, because the outbox delivers at least once
     * -- the source key is unique per tenant, and this check is what turns a
     * redelivery into a no-op rather than a constraint violation.
     *
     * <p>The position comes from the detection itself rather than a fresh
     * lookup. It is the point that left the corridor, which is the one this
     * ticket is about; by the time a redelivery runs, the latest fix could be
     * somewhere else entirely.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<UUID> raiseForRouteDeviation(UUID tripId, BigDecimal distanceM,
                                                 BigDecimal corridorM, BigDecimal lat,
                                                 BigDecimal lon, Instant detectedAt) {
        UUID tenantId = TenantContext.requireTenantId();
        String sourceKey = "route-deviation:" + tripId + ":" + detectedAt.toEpochMilli();

        Optional<ExceptionTicket> already = tickets.findBySourceKey(tenantId, sourceKey);
        if (already.isPresent()) {
            return already.map(ExceptionTicket::id);
        }

        TripLookupPort.TripSnapshot trip = trips.find(tripId)
                .orElseThrow(() -> new ResourceNotFoundException("Trip", tripId));

        ExceptionTicket.Position position = lat == null || lon == null
                ? ExceptionTicket.Position.none()
                : new ExceptionTicket.Position(lat, lon, ExceptionTicket.PositionSource.DETECTOR,
                        detectedAt);

        String description = "Vehicle was " + distanceM.stripTrailingZeros().toPlainString()
                + " m from the planned route; the corridor is "
                + corridorM.stripTrailingZeros().toPlainString() + " m";

        return Optional.of(open(tenantId, trip, null, ExceptionCategory.ROUTE_DEVIATION,
                description, ExceptionTicket.Source.ROUTE_DEVIATION, sourceKey, position,
                SYSTEM_ACTOR, Instant.now()));
    }

    /**
     * Turns another module's exception-like fact into a ticket.
     *
     * <p>Unguarded and idempotent on the source key, for the same reasons as
     * {@link #raiseForRouteDeviation}: the actor is the platform, and the
     * outbox delivers at least once, so a redelivery returns the ticket the
     * first delivery raised.
     *
     * <p>The trip is attached when the fact names one and execution still
     * knows it. A fact about a trip execution has never heard of is still
     * worth a ticket -- it is raised against its subject alone, rather than
     * failing the delivery and being retried forever. No position is
     * captured: these facts carry none, and by the time a republished event is
     * heard the vehicle's latest fix may be somewhere else entirely.
     *
     * <p>Joins the caller's transaction. The listeners open a new one, scoped
     * to the event's tenant, before calling.
     */
    @Transactional
    public UUID raiseDetected(DetectedException detected) {
        UUID tenantId = TenantContext.requireTenantId();

        Optional<ExceptionTicket> already = tickets.findBySourceKey(tenantId, detected.sourceKey());
        if (already.isPresent()) {
            return already.get().id();
        }

        TripLookupPort.TripSnapshot trip = detected.tripId() == null
                ? null
                : trips.find(detected.tripId()).orElse(null);

        return open(tenantId, trip, detected.subject(), detected.category(),
                detected.description(), detected.source(), detected.sourceKey(),
                ExceptionTicket.Position.none(), SYSTEM_ACTOR, Instant.now());
    }

    @Transactional
    @PreAuthorize("hasAuthority('EXCEPTION_MANAGE')")
    public void assign(UUID ticketId, UUID assigneeUserId, String note) {
        ExceptionTicket ticket = require(ticketId);
        Instant now = Instant.now();
        record(ticket, tickets.save(ticket.assignTo(assigneeUserId, now)), "ASSIGNED", note, now);
    }

    @Transactional
    @PreAuthorize("hasAuthority('EXCEPTION_MANAGE')")
    public void startInvestigation(UUID ticketId, String note) {
        ExceptionTicket ticket = require(ticketId);
        Instant now = Instant.now();
        record(ticket, tickets.save(ticket.startInvestigation(now)), "INVESTIGATION_STARTED",
                note, now);
    }

    @Transactional
    @PreAuthorize("hasAuthority('EXCEPTION_MANAGE')")
    public void resolve(UUID ticketId, String resolution) {
        ExceptionTicket ticket = require(ticketId);
        Instant now = Instant.now();
        record(ticket, tickets.save(ticket.resolve(resolution, currentActor(), now)),
                "RESOLVED", resolution, now);
    }

    /** A manager sends a resolution back. */
    @Transactional
    @PreAuthorize("hasAuthority('EXCEPTION_CLOSE')")
    public void reopen(UUID ticketId, String reason) {
        ExceptionTicket ticket = require(ticketId);
        Instant now = Instant.now();
        record(ticket, tickets.save(ticket.reopen(reason, now)), "REOPENED", reason, now);
    }

    /**
     * A manager closes the ticket, releasing anything it was holding up.
     *
     * <p>Audited with before and after state, because for a critical ticket
     * this is a financial act: it is the moment held settlement may proceed.
     * For the same reason it is subject to the tenant's access policies on the
     * ticket -- by default, closing one that holds settlement needs an approval
     * limit large enough to release money.
     */
    @Transactional
    @PreAuthorize("hasAuthority('EXCEPTION_CLOSE')"
            + " and @abac.permits('EXCEPTION_CLOSE', 'EXCEPTION_TICKET', #ticketId)")
    public void close(UUID ticketId, String note) {
        UUID tenantId = TenantContext.requireTenantId();
        ExceptionTicket ticket = require(ticketId);
        Instant now = Instant.now();
        ExceptionTicket closed = tickets.save(ticket.close(currentActor(), now));
        record(ticket, closed, "CLOSED", note, now);

        audit.record("EXCEPTION_CLOSED", "EXCEPTION_TICKET", closed.id(),
                Json.object("status", ticket.status().name(),
                        "resolvedBy", String.valueOf(ticket.resolvedBy())),
                Json.object("status", closed.status().name(),
                        "closedBy", closed.closedBy(),
                        "suspendedSettlement", String.valueOf(closed.suspendsSettlement())));

        events.publishEvent(new ExceptionClosed(tenantId, closed.id(), closed.ticketNo(),
                closed.tripId(), closed.suspendsSettlement(), closed.raisedAt(), now));
    }

    // ------------------------------------------------------------- internals

    /**
     * @param trip    null for a ticket about something other than a trip
     * @param subject null for a ticket about a trip alone
     */
    private UUID open(UUID tenantId, TripLookupPort.TripSnapshot trip,
                      ExceptionTicket.Subject subject, ExceptionCategory category,
                      String description, ExceptionTicket.Source source, String sourceKey,
                      ExceptionTicket.Position position, String actor, Instant now) {
        ExceptionTicket ticket = tickets.save(ExceptionTicket.raise(UUID.randomUUID(), tenantId,
                newTicketNo(), trip == null ? null : trip.tripId(),
                trip == null ? null : trip.tripNo(), trip == null ? null : trip.vehicleId(),
                subject, category, description, source, sourceKey, position, actor, now));

        activities.save(ExceptionActivity.of(tenantId, ticket.id(), "RAISED", null,
                ticket.status(), ticket.description(), actor, now));

        events.publishEvent(new ExceptionRaised(tenantId, ticket.id(), ticket.ticketNo(),
                ticket.tripId(), ticket.tripNo(), ticket.category().name(),
                ticket.severity().name(), ticket.suspendsSettlement(), ticket.lat(),
                ticket.lon(), ticket.raisedAt()));
        return ticket.id();
    }

    /**
     * The 3.8 capture: where the vehicle was when the ticket was raised.
     *
     * <p>A fresh telematics fix wins, then what the reporter supplied, then a
     * stale fix with its true timestamp, then an explicit NONE. Every branch
     * records when the position was true, so a reader can tell "here, now"
     * from "here, three hours ago".
     */
    private ExceptionTicket.Position capturePosition(UUID vehicleId, BigDecimal reportedLat,
                                                     BigDecimal reportedLon, Instant now) {
        if ((reportedLat == null) != (reportedLon == null)) {
            throw new BusinessRuleViolationException("position-incomplete",
                    "A reported position needs both a latitude and a longitude");
        }
        if (reportedLat != null && !plausible(reportedLat, reportedLon)) {
            throw new BusinessRuleViolationException("position-out-of-range",
                    "Reported position " + reportedLat + "," + reportedLon
                            + " is not a coordinate on Earth");
        }

        Optional<VehicleFixPort.Fix> fix = vehicleId == null
                ? Optional.empty()
                : fixes.latestFix(vehicleId);

        if (fix.isPresent() && !fix.get().recordedAt().isBefore(now.minus(maxFixAge))) {
            return telematics(fix.get());
        }
        if (reportedLat != null) {
            return new ExceptionTicket.Position(reportedLat, reportedLon,
                    ExceptionTicket.PositionSource.REPORTED, now);
        }
        return fix.map(ExceptionCommandService::telematics)
                .orElseGet(ExceptionTicket.Position::none);
    }

    private static ExceptionTicket.Position telematics(VehicleFixPort.Fix fix) {
        return new ExceptionTicket.Position(fix.lat(), fix.lon(),
                ExceptionTicket.PositionSource.TELEMATICS, fix.recordedAt());
    }

    private static boolean plausible(BigDecimal lat, BigDecimal lon) {
        return lat.abs().compareTo(BigDecimal.valueOf(90)) <= 0
                && lon.abs().compareTo(BigDecimal.valueOf(180)) <= 0;
    }

    private TripLookupPort.TripSnapshot requireRaisableTrip(UUID tripId) {
        TripLookupPort.TripSnapshot trip = trips.find(tripId)
                .orElseThrow(() -> new ResourceNotFoundException("Trip", tripId));
        if (trip.isCancelled()) {
            // Nothing is moving and nothing will be paid. A ticket here could
            // only ever be closed unread.
            throw new BusinessRuleViolationException("trip-cancelled",
                    "Trip " + trip.tripNo() + " is cancelled; there is nothing to raise against");
        }
        return trip;
    }

    private void record(ExceptionTicket before, ExceptionTicket after, String action, String note,
                        Instant at) {
        activities.save(ExceptionActivity.of(after.tenantId(), after.id(), action,
                before.status(), after.status(), note, currentActor(), at));
    }

    private ExceptionTicket require(UUID ticketId) {
        return tickets.findById(ticketId)
                .orElseThrow(() -> new ResourceNotFoundException("Exception", ticketId));
    }

    /**
     * Short enough to read down a telephone, random enough not to collide: a
     * per-tenant counter would need a lock on every raise, and a clash among
     * four billion values is caught by the unique constraint regardless.
     */
    private static String newTicketNo() {
        return "EX-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }

    private static String currentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null || authentication.getName() == null
                ? SYSTEM_ACTOR
                : authentication.getName();
    }
}
