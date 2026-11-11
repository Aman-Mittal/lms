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
package com.lms.approvals.command;

import java.util.HashMap;
import java.util.Map;

import com.lms.approvals.api.ApprovalOutcomeHandler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

/**
 * The owning modules' handlers, one per subject type.
 *
 * <p>Checked when the application starts, so a flow wired halfway fails the
 * build's first integration test rather than a decision in production: every
 * handler must name a subject type with a default policy, no type may have
 * two handlers, and every type with a default policy must have a handler.
 */
@Component
public class ApprovalHandlers implements SmartInitializingSingleton {

    private final ObjectProvider<ApprovalOutcomeHandler> handlers;
    private final Map<String, ApprovalOutcomeHandler> byType = new HashMap<>();

    /**
     * Lazily, through a provider: a handler depends on its owning module's
     * service, which raises requests through this module, so taking the
     * handlers in the constructor would be a cycle.
     */
    public ApprovalHandlers(ObjectProvider<ApprovalOutcomeHandler> handlers) {
        this.handlers = handlers;
    }

    @Override
    public void afterSingletonsInstantiated() {
        handlers.orderedStream().forEach(handler -> {
            String type = handler.subjectType();
            if (ApprovalPolicyDefaults.of(type).isEmpty()) {
                throw new IllegalStateException("Approval handler " + handler.getClass().getName()
                        + " names subject type " + type + ", which has no default policy");
            }
            if (byType.putIfAbsent(type, handler) != null) {
                throw new IllegalStateException("Two approval handlers for subject type " + type);
            }
        });
        for (String type : ApprovalPolicyDefaults.subjectTypes()) {
            if (!byType.containsKey(type)) {
                throw new IllegalStateException("No approval handler is registered for subject type " + type);
            }
        }
    }

    public ApprovalOutcomeHandler of(String subjectType) {
        ApprovalOutcomeHandler handler = byType.get(subjectType);
        if (handler == null) {
            throw new IllegalStateException("No approval handler for subject type " + subjectType);
        }
        return handler;
    }
}
