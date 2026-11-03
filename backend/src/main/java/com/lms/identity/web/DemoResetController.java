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
package com.lms.identity.web;

import java.time.Instant;

import com.lms.identity.command.DemoResetService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Puts the demo tenant back to its seeded state. Authorisation is in the service. */
@RestController
@RequestMapping("/api/v1/admin")
public class DemoResetController {

    private final DemoResetService resets;

    public DemoResetController(DemoResetService resets) {
        this.resets = resets;
    }

    @PostMapping("/demo-reset")
    public DemoReset reset(@RequestBody(required = false) DemoResetRequest request) {
        DemoResetService.Outcome outcome = resets.reset(request == null ? null : request.confirm());
        return new DemoReset(outcome.tenantCode(), outcome.rowsDeleted(), outcome.signInAgain(),
                outcome.resetAt());
    }

    /** @param confirm the demo tenant's code, typed by the caller */
    public record DemoResetRequest(String confirm) {
    }

    /**
     * @param signInAgain true when the caller's own user was recreated, so the
     *                    token in hand names a user that no longer exists
     */
    public record DemoReset(String tenantCode, long rowsDeleted, boolean signInAgain, Instant resetAt) {
    }
}
