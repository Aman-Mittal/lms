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
package com.lms.clientbilling.query;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Which client the signed-in user is confined to, if any.
 *
 * <p>A user bound in {@code client_portal_user} is a client's own user and
 * sees that client's contracts, charges and invoices and nothing else. A user
 * who is not bound is the 3PL's own staff, governed by permissions alone.
 *
 * <p>Enforced in this module's services, on every read and every command,
 * because nothing upstream knows about clients yet. Partner-scoped ABAC is
 * meant to take this over and apply the same confinement to orders and stock;
 * until then this is the one place the rule lives, and a client-billing
 * method that does not call it is a leak.
 *
 * <p>Must be called inside a transaction: the binding is a tenant-scoped row,
 * and outside one row-level security returns nothing -- which would read as
 * "not bound" and grant staff visibility. That failure mode is why
 * {@link #boundClient()} is only ever consulted from transactional services.
 */
@Component
public class ClientAccess {

    private final JdbcClient jdbc;

    public ClientAccess(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The client the current user is confined to, or empty for 3PL staff. */
    public Optional<UUID> boundClient() {
        UUID userId = currentUserId();
        if (userId == null) {
            return Optional.empty();
        }
        return jdbc.sql("SELECT client_partner_id FROM client_portal_user WHERE user_id = :userId")
                .param("userId", userId)
                .query(UUID.class)
                .optional();
    }

    /**
     * Refuses a portal user acting on any client but their own.
     *
     * <p>A refusal rather than an empty answer: asking for another client by
     * identifier is a deliberate act, and saying "denied" is more useful to
     * whoever reads the audit than an empty list that looks like no data.
     */
    public void requireAccessTo(UUID clientPartnerId) {
        boundClient().ifPresent(bound -> {
            if (!bound.equals(clientPartnerId)) {
                throw new AccessDeniedException(
                        "A client portal user may only act on their own client's records");
            }
        });
    }

    /** Refuses portal users outright, for the 3PL's own numbers. */
    public void refusePortalUsers(String what) {
        if (boundClient().isPresent()) {
            throw new AccessDeniedException(what + " is not visible to a client portal user");
        }
    }

    /**
     * The principal's user identifier, or null when it is not one -- a test
     * principal, or the platform acting on an event.
     */
    private static UUID currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getName() == null) {
            return null;
        }
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
