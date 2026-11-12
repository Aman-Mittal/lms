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
package com.lms.execution.query;

import java.util.UUID;

/**
 * What the gate would decide for a vehicle presenting (or lacking) a pass:
 * {@code admits} with the pass that would be used, or the refusal's
 * {@code code} (gate-pass-required, gate-pass-expired, gate-pass-used,
 * gate-pass-cancelled, gate-pass-trip-mismatch) and why. Nothing is consumed.
 * {@code passRequired} is what the site's unit demands.
 */
public record GatePassCheckView(
        boolean admits,
        boolean passRequired,
        UUID passId,
        String passNo,
        String code,
        String message) {
}
