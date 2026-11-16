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
package com.lms.statutory.command;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.regex.Pattern;

import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.statutory.api.EwayBillAction;

/**
 * The rules of an e-way bill's life, apart from any adapter or database: when
 * an action is allowed, how long a bill is valid, when it is expiring. Every
 * refusal is a stable problem code.
 */
final class EwayBillRules {

    private static final Pattern VEHICLE = Pattern.compile("[A-Z0-9-]{4,20}");

    private EwayBillRules() {
    }

    /** Days a bill is valid for a journey: the distance over the daily allowance, rounded up, at least one. */
    static int validityDays(int distanceKm, int kmPerDay) {
        if (distanceKm < 1 || kmPerDay < 1) {
            throw new IllegalArgumentException("distance and kilometres per day must be positive");
        }
        return Math.max(1, (distanceKm + kmPerDay - 1) / kmPerDay);
    }

    /** When a bill generated at {@code generatedAt} for the distance lapses. */
    static Instant validUntil(Instant generatedAt, int distanceKm, int kmPerDay) {
        return generatedAt.plus(Duration.ofDays(validityDays(distanceKm, kmPerDay)));
    }

    /** Whether a bill can still be used at {@code at}: live, and valid after that moment. */
    static boolean usableAt(EwayBillStatus status, Instant validUntil, Instant at) {
        return status.isLive() && validUntil.isAfter(at);
    }

    /** Whether a live bill is within {@code thresholdHours} of lapsing (and has not lapsed). */
    static boolean expiringSoon(Instant validUntil, Instant now, int thresholdHours) {
        return validUntil.isAfter(now) && !validUntil.isAfter(now.plus(Duration.ofHours(thresholdHours)));
    }

    /**
     * Whether the action may be applied to a bill now, or the code that says why not.
     *
     * @param hasVehicle whether Part-B already names a vehicle
     * @throws BusinessRuleViolationException with the problem code
     */
    static void requireAllowed(EwayBillAction action, EwayBillStatus status, Instant generatedAt, Instant validUntil,
                               boolean hasVehicle, Instant now, int cancelWindowHours) {
        if (action == EwayBillAction.GENERATE || action == EwayBillAction.FETCH) {
            throw new BusinessRuleViolationException("eway-action-not-applicable",
                    action + " raises a bill; it is not an action on an existing one");
        }
        if (!status.isLive()) {
            throw new BusinessRuleViolationException("eway-bill-not-live",
                    "The bill is " + status + "; only an active or extended bill can be changed");
        }
        if (!validUntil.isAfter(now)) {
            throw new BusinessRuleViolationException("eway-bill-expired",
                    "The bill's validity has passed, so it can no longer be changed");
        }
        switch (action) {
            case CANCEL -> {
                Instant lastCancel = generatedAt.plus(Duration.ofHours(cancelWindowHours));
                if (now.isAfter(lastCancel)) {
                    throw new BusinessRuleViolationException("eway-cancel-window-passed",
                            "A bill can be cancelled only within " + cancelWindowHours
                                    + " hours of generation; this one's window closed at " + lastCancel);
                }
            }
            case CHANGE_VEHICLE -> {
                if (!hasVehicle) {
                    throw new BusinessRuleViolationException("eway-part-b-missing",
                            "No vehicle is on Part-B yet; update Part-B before changing the vehicle");
                }
            }
            default -> {
                // PART_B and EXTEND need nothing more of the bill itself.
            }
        }
    }

    /** An extension, cancellation or vehicle change must say why. */
    static String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessRuleViolationException("eway-reason-required", "A reason is required for this action");
        }
        String trimmed = reason.trim();
        if (trimmed.length() > 500) {
            throw new BusinessRuleViolationException("eway-reason-too-long", "The reason is at most 500 characters");
        }
        return trimmed;
    }

    /** A vehicle registration as the statutory system holds it: upper case, without spaces. */
    static String requireVehicle(String vehicleNo) {
        if (vehicleNo == null || vehicleNo.isBlank()) {
            throw new BusinessRuleViolationException("eway-vehicle-required", "A vehicle number is required");
        }
        String normal = vehicleNo.replace(" ", "").toUpperCase(Locale.ROOT);
        if (!VEHICLE.matcher(normal).matches()) {
            throw new BusinessRuleViolationException("eway-vehicle-invalid",
                    "A vehicle number is 4 to 20 letters, digits or hyphens");
        }
        return normal;
    }

    /** A credential must be a reference to a secret, never the secret. */
    static String requireReference(String credentialRef) {
        if (credentialRef != null && !credentialRef.startsWith("secret-ref:")) {
            throw new BusinessRuleViolationException("eway-credential-not-a-reference",
                    "The unit's credentials must be a reference such as secret-ref:eway/branch-1");
        }
        return credentialRef;
    }
}
