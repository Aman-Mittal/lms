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

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.lms.masterdata.api.TerminalGeofencePort;
import com.lms.masterdata.command.domain.Terminal;
import com.lms.shared.geo.LatLon;
import com.lms.shared.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Serves {@link TerminalGeofencePort} from the terminal register. */
@Service
@Transactional(readOnly = true)
public class TerminalGeofenceService implements TerminalGeofencePort {

    private final TerminalRepository terminals;

    public TerminalGeofenceService(TerminalRepository terminals) {
        this.terminals = terminals;
    }

    @Override
    public List<TerminalMatch> terminalsContaining(LatLon point) {
        UUID tenantId = TenantContext.requireTenantId();

        // Phase one is the indexed range query on the precomputed bounding
        // boxes; phase two is exact geometry over whatever survives it. On a
        // 0.1-CPU instance the ordering is what makes this affordable per ping.
        return terminals.findCandidatesContaining(tenantId,
                        BigDecimal.valueOf(point.lat()), BigDecimal.valueOf(point.lon()))
                .stream()
                .filter(terminal -> terminal.contains(point))
                .map(terminal -> new TerminalMatch(terminal.id(), terminal.code(),
                        terminal.functionalCategory().name()))
                .toList();
    }

    @Override
    public Optional<LatLon> centreOf(UUID terminalId) {
        return terminals.findById(terminalId).map(Terminal::centre);
    }
}
