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
package com.lms.events.command;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the one thing this module needs from an event it never imports: the
 * tenant it belongs to.
 *
 * <p>Every event record in the platform starts with {@code UUID tenantId}, and
 * the registry stores events as JSON, so the tenant is the payload's top-level
 * {@code tenantId} field. An event without one -- or with one that is not a
 * UUID -- has no tenant, which callers report rather than guess at.
 */
@Component
public class EventPayloads {

    private final JsonMapper json;

    public EventPayloads(JsonMapper json) {
        this.json = json;
    }

    public Optional<UUID> tenantOf(String serializedEvent) {
        if (serializedEvent == null || serializedEvent.isBlank()) {
            return Optional.empty();
        }
        try {
            return tenantOf(json.readTree(serializedEvent));
        } catch (JacksonException e) {
            return Optional.empty();
        }
    }

    public Optional<UUID> tenantOf(JsonNode event) {
        JsonNode node = event == null ? null : event.get("tenantId");
        if (node == null || !node.isString()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(node.asString()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** The event as a tree, the way the registry serializes it (with the same mapper). */
    public JsonNode treeOf(Object event) {
        return json.valueToTree(event);
    }

    /** The serialized form as a tree, or null if it is not JSON. */
    public JsonNode parse(String serializedEvent) {
        try {
            return json.readTree(serializedEvent);
        } catch (JacksonException e) {
            return null;
        }
    }
}
