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
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.tenant.TenantContext;
import org.springframework.context.ApplicationEventPublisher;
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

    public TerminalCommandService(TerminalRepository terminals, ApplicationEventPublisher events) {
        this.terminals = terminals;
        this.events = events;
    }

    @Transactional
    @PreAuthorize("hasAuthority('TERMINAL_CREATE')")
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
