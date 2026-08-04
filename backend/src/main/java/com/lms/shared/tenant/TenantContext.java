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
package com.lms.shared.tenant;

import java.util.Optional;
import java.util.UUID;

/**
 * Holds the tenant and organisational scope of the current request.
 *
 * <p>This is layer two of the three-layer isolation described in
 * DOCS/adr/0005. It is <em>not</em> the security boundary -- Postgres row-level
 * security is, and it reads the value this class publishes onto the connection.
 * Treating this as sufficient on its own is the mistake the RLS layer exists to
 * survive.
 *
 * <p>Backed by a {@link ScopedValue}-style {@code ThreadLocal}. Virtual threads
 * are enabled ({@code spring.threads.virtual.enabled}), and each request runs on
 * its own virtual thread, so a thread-local remains correct -- but it does not
 * propagate across an explicit thread hand-off. Any asynchronous work that needs
 * tenant scope must be given it explicitly; see
 * {@link #callWith(UUID, String, java.util.function.Supplier)}.
 */
public final class TenantContext {

    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    /**
     * The tenant and organisational path in effect for this thread.
     *
     * @param tenantId the owning tenant
     * @param orgPath  materialised path of the caller's organisational unit,
     *                 e.g. {@code /hq/north/branch-7/}. Visibility is every unit
     *                 at or below this path.
     */
    public record Scope(UUID tenantId, String orgPath) {
        public Scope {
            if (tenantId == null) {
                throw new IllegalArgumentException("tenantId must not be null");
            }
        }
    }

    public static void set(UUID tenantId, String orgPath) {
        CURRENT.set(new Scope(tenantId, orgPath));
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static Optional<Scope> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /**
     * The current tenant id.
     *
     * @throws IllegalStateException if no tenant is bound. This is deliberately
     *         fatal rather than defaulting: silently continuing without a tenant
     *         is how cross-tenant leaks happen.
     */
    public static UUID requireTenantId() {
        Scope scope = CURRENT.get();
        if (scope == null) {
            throw new IllegalStateException(
                    "No tenant bound to the current thread. A request-scoped operation ran outside "
                            + "the tenant filter, or asynchronous work was started without propagating "
                            + "the tenant scope.");
        }
        return scope.tenantId();
    }

    public static String requireOrgPath() {
        Scope scope = CURRENT.get();
        if (scope == null || scope.orgPath() == null) {
            throw new IllegalStateException("No organisational scope bound to the current thread");
        }
        return scope.orgPath();
    }

    /**
     * Runs an action under an explicit tenant scope, restoring whatever was
     * previously bound.
     *
     * <p>The way to give scheduled jobs and event listeners a tenant, since they
     * do not run inside the request filter.
     */
    public static <T> T callWith(UUID tenantId, String orgPath, java.util.function.Supplier<T> action) {
        Scope previous = CURRENT.get();
        CURRENT.set(new Scope(tenantId, orgPath));
        try {
            return action.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    public static void runWith(UUID tenantId, String orgPath, Runnable action) {
        callWith(tenantId, orgPath, () -> {
            action.run();
            return null;
        });
    }
}
