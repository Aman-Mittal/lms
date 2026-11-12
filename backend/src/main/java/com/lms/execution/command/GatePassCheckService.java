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
package com.lms.execution.command;

import java.time.Instant;
import java.util.UUID;

import com.lms.execution.command.domain.GatePass;
import com.lms.execution.command.domain.Registrations;
import com.lms.masterdata.api.FleetCapacityPort;
import com.lms.masterdata.api.TerminalOrgUnitPort;
import com.lms.settings.api.SettingContext;
import com.lms.settings.api.Settings;
import com.lms.shared.error.ResourceNotFoundException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The gate desk's "would this vehicle be let in": the verdict of the entry's own
 * pass rule, with nothing consumed and nothing written.
 */
@Service
public class GatePassCheckService {

    /** @param passRequired whether the site's unit demands a pass; the other fields are as {@link GatePassDesk.Verdict} */
    public record Admission(boolean admits, boolean passRequired, UUID passId, String passNo, String code,
                            String message) {
    }

    private final GatePassDesk desk;
    private final FleetCapacityPort fleet;
    private final TerminalOrgUnitPort terminals;
    private final Settings settings;

    GatePassCheckService(GatePassDesk desk, FleetCapacityPort fleet, TerminalOrgUnitPort terminals,
                         Settings settings) {
        this.desk = desk;
        this.fleet = fleet;
        this.terminals = terminals;
        this.settings = settings;
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyAuthority('GATE_REGISTER', 'GATE_PASS_MANAGE')")
    public Admission check(UUID orgUnitId, UUID terminalId, UUID vehicleId, String registrationNo, UUID tripId,
                           String passNo, Instant at) {
        UUID unit = orgUnitId;
        if (terminalId != null) {
            unit = terminals.orgUnitOf(terminalId).orElseThrow(() -> new ResourceNotFoundException("Terminal", terminalId));
        }
        if (unit == null) {
            throw new IllegalArgumentException("A pass check names its site: an organisational unit or a terminal");
        }
        String registration = vehicleId != null
                ? Registrations.normalise(fleet.registrationOf(vehicleId)
                        .orElseThrow(() -> new ResourceNotFoundException("Vehicle", vehicleId)))
                : Registrations.normalise(registrationNo);
        if (registration == null) {
            throw new IllegalArgumentException("A pass check names a registered vehicle or a registration number");
        }
        boolean required = settings.effective(ExecutionSettingDefinitions.GATE_PASS_REQUIRED,
                SettingContext.ofOrgUnit(unit));
        GatePassDesk.Verdict verdict = desk.evaluate(unit, terminalId, registration, passNo, tripId,
                at == null ? Instant.now() : at);
        GatePass pass = verdict.pass();
        return new Admission(verdict.admits(), required, pass == null ? null : pass.id(),
                pass == null ? null : pass.passNo(), verdict.code(), verdict.message());
    }
}
