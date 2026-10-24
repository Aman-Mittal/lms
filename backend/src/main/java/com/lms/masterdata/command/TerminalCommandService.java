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
package com.lms.masterdata.command;

import java.util.List;
import java.util.UUID;

import com.lms.masterdata.command.domain.Terminal;
import com.lms.masterdata.events.TerminalRegistered;
import com.lms.shared.audit.AuditTrail;
import com.lms.shared.config.Json;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.tenant.TenantContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Write side for terminals.
 *
 * <p>Enforces the rule from vision document 3.2.3 that a new terminal must not
 * intersect or overlap an existing terminal of the same functional category.
 */
@Service
public class TerminalCommandService {

    private final TerminalRepository terminals;
    private final ApplicationEventPublisher events;
    private final TerminalReferenceCheck references;
    private final AuditTrail audit;

    public TerminalCommandService(TerminalRepository terminals, ApplicationEventPublisher events,
                                  TerminalReferenceCheck references, AuditTrail audit) {
        this.terminals = terminals;
        this.events = events;
        this.references = references;
        this.audit = audit;
    }

    @Transactional
    @PreAuthorize("hasAuthority('TERMINAL_CREATE')"
            + " and @abac.permitsIn('TERMINAL_CREATE', #command?.orgUnitId())")
    public UUID register(RegisterTerminalCommand command) {
        UUID tenantId = TenantContext.requireTenantId();

        terminals.findByCode(tenantId, command.code()).ifPresent(existing -> {
            throw new BusinessRuleViolationException("terminal-code-taken",
                    "A terminal with code " + command.code() + " already exists");
        });

        Terminal terminal = build(UUID.randomUUID(), tenantId, command);
        rejectIfOverlapping(tenantId, terminal, null);

        Terminal saved = terminals.save(terminal);
        events.publishEvent(TerminalRegistered.from(
                saved.tenantId(), saved.id(), saved.code(), saved.name(),
                saved.functionalCategory().name(), saved.geofenceType().name()));
        return saved.id();
    }

    /**
     * Changes a terminal in place. The code, category and organisational unit
     * are fixed; everything else may move, including the geofence.
     *
     * <p>Writes are held to the same organisational-subtree policy as
     * registration ({@code TERMINAL_CREATE}'s), applied to the terminal's own
     * unit. The save is guarded on the version the caller read, so two people
     * editing the same terminal cannot silently overwrite each other: the
     * second gets 409 {@code concurrent-modification}.
     */
    @Transactional
    @PreAuthorize("hasAuthority('TERMINAL_UPDATE')"
            + " and @abac.permits('TERMINAL_CREATE', 'TERMINAL', #command?.id())")
    public void update(UpdateTerminalCommand command) {
        UUID tenantId = TenantContext.requireTenantId();
        Terminal current = terminals.findById(command.id())
                .orElseThrow(() -> new ResourceNotFoundException("Terminal", command.id()));

        String[] vehicleTypes = command.permittedVehicleTypes() == null
                ? null
                : command.permittedVehicleTypes().toArray(String[]::new);
        Terminal revised = current.revised(command.name(), command.dockCount(), command.opensAt(),
                command.closesAt(), command.avgDwellMinutes(), vehicleTypes, command.version());
        revised = switch (command.geofence()) {
            case null -> revised;
            case UpdateTerminalCommand.Polygon polygon -> revised.withPolygon(polygon.ring());
            case UpdateTerminalCommand.PointRadius circle ->
                    revised.withPointRadius(circle.centre(), circle.radiusMetres());
        };
        if (command.geofence() != null) {
            // Excluding itself: a terminal's old footprint is not a clash.
            rejectIfOverlapping(tenantId, revised, current.id());
        }

        Terminal saved = terminals.save(revised);
        audit.recordAtomically("TERMINAL_UPDATED", "TERMINAL", saved.id(), snapshot(current), snapshot(saved));
    }

    /**
     * Removes a terminal that nothing refers to.
     *
     * <p>Refused with 409 {@code terminal-in-use} while any trip, load,
     * consignment, itinerary, milk-run record, warehouse record, rate or other
     * row still names it. The service checks first so the answer is a clear
     * one; the database's RESTRICT foreign keys are the backstop, and a
     * reference created between the check and the delete is turned into the
     * same answer rather than a 500.
     */
    @Transactional
    @PreAuthorize("hasAuthority('TERMINAL_DELETE')"
            + " and @abac.permits('TERMINAL_CREATE', 'TERMINAL', #id)")
    public void delete(UUID id) {
        UUID tenantId = TenantContext.requireTenantId();
        Terminal terminal = terminals.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Terminal", id));

        List<String> usedBy = references.usedBy(tenantId, id);
        if (!usedBy.isEmpty()) {
            throw inUse(terminal, usedBy);
        }

        try {
            terminals.delete(terminal);
        } catch (DataIntegrityViolationException e) {
            if (isForeignKeyViolation(e)) {
                throw inUse(terminal, List.of("other records"));
            }
            throw e;
        }
        audit.recordAtomically("TERMINAL_DELETED", "TERMINAL", id, snapshot(terminal), null);
    }

    private static BusinessRuleViolationException inUse(Terminal terminal, List<String> usedBy) {
        return new BusinessRuleViolationException("terminal-in-use",
                "Terminal " + terminal.code() + " cannot be deleted: it is still used by "
                        + String.join(", ", usedBy));
    }

    private static boolean isForeignKeyViolation(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.sql.SQLException sql && "23503".equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    private static Json snapshot(Terminal terminal) {
        return Json.object("code", terminal.code(), "name", terminal.name(),
                "category", terminal.functionalCategory().name(),
                "geofenceType", terminal.geofenceType().name(),
                "dockCount", terminal.dockCount() == null ? null : terminal.dockCount().toString(),
                "opensAt", terminal.opensAt() == null ? null : terminal.opensAt().toString(),
                "closesAt", terminal.closesAt() == null ? null : terminal.closesAt().toString(),
                "avgDwellMinutes", terminal.avgDwellMinutes() == null ? null
                        : terminal.avgDwellMinutes().toString(),
                "centreLat", terminal.centreLat() == null ? null : terminal.centreLat().toPlainString(),
                "centreLon", terminal.centreLon() == null ? null : terminal.centreLon().toPlainString(),
                "radiusM", terminal.radiusM() == null ? null : terminal.radiusM().toPlainString(),
                "polygon", terminal.polygon() == null ? null : terminal.polygon().value());
    }

    /**
     * Rejects a terminal that shares area with an existing one of the same
     * category.
     *
     * <p>Two phases, per DOCS/adr/0004: an indexed bounding-box query narrows
     * the field, then exact geometry runs in Java over what survives. The box
     * test alone would produce false positives -- two L-shaped yards can have
     * intersecting boxes and no common ground -- so it is a filter, never the
     * answer.
     */
    private void rejectIfOverlapping(UUID tenantId, Terminal candidate, UUID excludeId) {
        List<Terminal> nearby = terminals.findOverlapCandidates(
                tenantId,
                candidate.functionalCategory().name(),
                candidate.minLat(), candidate.maxLat(),
                candidate.minLon(), candidate.maxLon(),
                excludeId);

        nearby.stream()
                .filter(candidate::overlaps)
                .findFirst()
                .ifPresent(clash -> {
                    throw new BusinessRuleViolationException("terminal-overlap",
                            "Terminal " + candidate.code() + " overlaps existing terminal "
                                    + clash.code() + ", which is also a "
                                    + candidate.functionalCategory()
                                    + ". Terminals of the same functional category must not overlap.");
                });
    }

    private static Terminal build(UUID id, UUID tenantId, RegisterTerminalCommand command) {
        String[] vehicleTypes = command.permittedVehicleTypes() == null
                ? null
                : command.permittedVehicleTypes().toArray(String[]::new);

        return switch (command) {
            case RegisterTerminalCommand.Polygon polygon -> Terminal.ofPolygon(
                    id, tenantId, polygon.orgUnitId(), polygon.code(), polygon.name(),
                    polygon.category(), polygon.ring(), polygon.dockCount(),
                    polygon.opensAt(), polygon.closesAt(), polygon.avgDwellMinutes(), vehicleTypes);
            case RegisterTerminalCommand.PointRadius circle -> Terminal.ofPointRadius(
                    id, tenantId, circle.orgUnitId(), circle.code(), circle.name(),
                    circle.category(), circle.centre(), circle.radiusMetres(), circle.dockCount(),
                    circle.opensAt(), circle.closesAt(), circle.avgDwellMinutes(), vehicleTypes);
        };
    }
}
