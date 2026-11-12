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
import java.util.List;
import java.util.UUID;

import com.lms.execution.command.domain.GateVisit;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The gate desk's commands: a vehicle enters, a vehicle leaves (issue #223).
 *
 * <p>Held to the organisational unit of the site by the access policies
 * ({@code GATE_REGISTER} on the unit the vehicle enters, or the visit's own
 * unit for an exit). Each call is one transaction holding the visit, the
 * pass's use, the audit row and the event; see {@link GateRegister}.
 */
@Service
public class GateRegisterService {

    /**
     * An entry.
     *
     * @param orgUnitId      the site's unit; may be omitted when a terminal is named
     * @param terminalId     the site's terminal, optional
     * @param vehicleId      a registered vehicle; omitted for a visitor
     * @param registrationNo a visitor's registration number (ignored when {@code vehicleId} names one)
     * @param gatePassNo     the pass presented, if any
     * @param at             when it entered; now when null
     */
    public record EnterCommand(UUID orgUnitId, UUID terminalId, UUID vehicleId, String registrationNo,
                               UUID driverId, String driverName, GateVisit.Purpose purpose, UUID tripId,
                               String gatePassNo, Instant at, List<String> sealNos, String weightRef,
                               String remarks) {
    }

    private final GateRegister register;

    public GateRegisterService(GateRegister register) {
        this.register = register;
    }

    @Transactional
    @PreAuthorize("hasAuthority('GATE_REGISTER')"
            + " and @abac.permitsIn('GATE_REGISTER', @gateSites.unitOf(#command.orgUnitId(), #command.terminalId()))")
    public UUID enter(EnterCommand command) {
        return register.enter(new GateRegister.Entry(command.orgUnitId(), command.terminalId(),
                command.vehicleId(), command.registrationNo(), command.driverId(), command.driverName(),
                command.purpose(), command.tripId(), command.gatePassNo(), command.at(), command.sealNos(),
                command.weightRef(), command.remarks())).id();
    }

    @Transactional
    @PreAuthorize("hasAuthority('GATE_REGISTER') and @abac.permitsIn('GATE_REGISTER', @gateSites.unitOfVisit(#visitId))")
    public void exit(UUID visitId, Instant at, List<String> sealNos, String weightRef, String remarks) {
        register.exit(visitId, at, sealNos, weightRef, remarks);
    }
}
