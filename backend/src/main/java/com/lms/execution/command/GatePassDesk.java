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
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.lms.execution.command.domain.GatePass;
import com.lms.execution.command.domain.Registrations;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.tenant.TenantContext;
import org.springframework.stereotype.Component;

/**
 * Judges whether a vehicle may be let in on a gate pass at a moment.
 *
 * <p>One place for the rule, used by the entry itself and by the desk's "check
 * a pass" read, so what the clerk is told before letting a lorry in is what
 * the entry would decide. Joins the caller's transaction and checks no
 * permission.
 *
 * <p>With a pass number the answer is about that pass. Without one, the
 * vehicle's passes at the unit are searched: one that admits wins; otherwise
 * the most telling refusal -- used, then expired, then cancelled -- and with no
 * pass at all, {@code gate-pass-required}.
 */
@Component
class GatePassDesk {

    private final GatePassRepository passes;

    GatePassDesk(GatePassRepository passes) {
        this.passes = passes;
    }

    /**
     * @param pass    the pass that admits, or the one that was refused; null when none was found
     * @param code    the refusal's problem code; null when the pass admits
     * @param message why, in words for the gate
     */
    record Verdict(GatePass pass, String code, String message) {

        boolean admits() {
            return code == null;
        }

        void requireAdmits() {
            if (code != null) {
                throw new BusinessRuleViolationException(code, message);
            }
        }
    }

    Verdict evaluate(UUID orgUnitId, UUID terminalId, String registrationNo, String passNo, UUID tripId,
                     Instant at) {
        UUID tenantId = TenantContext.requireTenantId();
        String registration = Registrations.normalise(registrationNo);
        if (passNo != null && !passNo.isBlank()) {
            Optional<GatePass> found = passes.findByNumber(tenantId, passNo.strip())
                    .filter(pass -> pass.registrationNo().equals(registration)
                            && covers(pass, orgUnitId, terminalId));
            if (found.isEmpty()) {
                return new Verdict(null, "gate-pass-required",
                        "Gate pass " + passNo.strip() + " does not cover vehicle " + registration
                                + " at this site");
            }
            GatePass pass = found.get();
            if (!tripCompatible(pass, tripId)) {
                return new Verdict(pass, "gate-pass-trip-mismatch",
                        "Gate pass " + pass.passNo() + " was issued for another trip");
            }
            String refusal = pass.refusalAt(at);
            return refusal == null ? new Verdict(pass, null, null)
                    : new Verdict(pass, refusal, refusalMessage(pass, refusal));
        }

        List<GatePass> candidates = passes.findForVehicle(tenantId, registration, orgUnitId).stream()
                .filter(pass -> covers(pass, orgUnitId, terminalId) && tripCompatible(pass, tripId))
                .toList();
        Optional<GatePass> admitting = candidates.stream().filter(pass -> pass.admitsAt(at))
                .min(Comparator.comparing(GatePass::validUntil));
        if (admitting.isPresent()) {
            return new Verdict(admitting.get(), null, null);
        }
        return candidates.stream()
                .max(Comparator.comparingInt((GatePass pass) -> rank(pass.refusalAt(at)))
                        .thenComparing(GatePass::validUntil))
                .map(pass -> new Verdict(pass, pass.refusalAt(at), refusalMessage(pass, pass.refusalAt(at))))
                .orElseGet(() -> new Verdict(null, "gate-pass-required",
                        "Vehicle " + registration + " has no gate pass for this site"));
    }

    private static boolean covers(GatePass pass, UUID orgUnitId, UUID terminalId) {
        return pass.orgUnitId().equals(orgUnitId)
                && (pass.terminalId() == null || pass.terminalId().equals(terminalId));
    }

    private static boolean tripCompatible(GatePass pass, UUID tripId) {
        return pass.tripId() == null || tripId == null || pass.tripId().equals(tripId);
    }

    private static int rank(String refusal) {
        return switch (refusal == null ? "" : refusal) {
            case "gate-pass-used" -> 3;
            case "gate-pass-expired" -> 2;
            default -> 1;
        };
    }

    private static String refusalMessage(GatePass pass, String code) {
        return switch (code) {
            case "gate-pass-used" -> "Gate pass " + pass.passNo() + " has already been used";
            case "gate-pass-cancelled" -> "Gate pass " + pass.passNo() + " was cancelled";
            default -> "Gate pass " + pass.passNo() + " is valid from " + pass.validFrom() + " until "
                    + pass.validUntil();
        };
    }
}
