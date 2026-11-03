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
package com.lms.e2e;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.UUID;
import java.util.regex.Pattern;

import com.lms.client.ApiClient;
import com.lms.client.api.AuthenticationApi;
import com.lms.client.model.AuthResult;
import com.lms.client.model.LoginRequest;
import com.lms.shared.tenant.TenantContext;
import feign.FeignException;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * What the money-and-stock end-to-end scenarios share beyond {@link HttpWorld}:
 * callers with only some permissions, a caller in another organisational
 * unit, an administrator of another tenant, a ledger fact dated last month,
 * and the assertion that a refusal is a problem document with a stable code.
 *
 * <p>The API has no user, role, unit or tenant endpoints yet -- identity is
 * seeded -- so these are provisioned in-process exactly as
 * {@code OrderToBillSteps} provisions its second manager; every caller then
 * signs in and acts over HTTP like anybody else. Everything created is
 * salted, so nothing here touches another scenario's rows.
 */
final class MoneyStockSupport {

    private final HttpWorld world;
    private final int port;
    private final JdbcClient jdbc;
    private final PasswordEncoder passwordEncoder;
    private final TransactionTemplate transactions;

    MoneyStockSupport(HttpWorld world, int port, JdbcClient jdbc, PasswordEncoder passwordEncoder,
                      PlatformTransactionManager transactionManager) {
        this.world = world;
        this.port = port;
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    // ------------------------------------------------------------- callers

    /** A signed-in user of the demo tenant holding exactly these permissions, in the caller's unit. */
    AuthResult userWith(String tag, String... permissions) {
        return userIn(world.orgUnitId(), tag, permissions);
    }

    /** As {@link #userWith}, in the given organisational unit of the demo tenant. */
    AuthResult userIn(UUID orgUnitId, String tag, String... permissions) {
        UUID tenantId = demoTenantId();
        String email = "ms-" + tag + "-" + world.salt.toLowerCase() + "@demo.local";
        String password = "money stock " + tag + " " + world.salt;
        TenantContext.runWith(tenantId, null, () -> transactions.executeWithoutResult(status ->
                provisionUser(tenantId, orgUnitId, email, password, tag, permissions)));
        return signIn(HttpWorld.DEMO_TENANT, email, password);
    }

    /** The id of a user created by {@link #userWith}, for binding to a client. */
    static UUID idOf(AuthResult user) {
        return user.getUserId();
    }

    /** A new branch beneath the caller's unit: a sibling of nothing the scenario owns. */
    UUID branchUnit(String tag) {
        UUID tenantId = demoTenantId();
        UUID id = UUID.randomUUID();
        String slug = "ms-" + tag + "-" + world.salt.toLowerCase();
        TenantContext.runWith(tenantId, null, () -> transactions.executeWithoutResult(status -> {
            String parentPath = jdbc.sql("SELECT path FROM org_unit WHERE id = :id")
                    .param("id", world.orgUnitId()).query(String.class).single();
            jdbc.sql("""
                            INSERT INTO org_unit (id, tenant_id, parent_id, slug, path, name, unit_type)
                            VALUES (:id, :tenantId, :parentId, :slug, :path, :name, 'BRANCH')
                            """)
                    .param("id", id).param("tenantId", tenantId)
                    .param("parentId", world.orgUnitId()).param("slug", slug)
                    .param("path", parentPath + slug + "/").param("name", "Branch " + tag)
                    .update();
        }));
        return id;
    }

    /** The administrator of a tenant of its own, holding every permission. */
    AuthResult otherTenantAdministrator() {
        UUID tenantId = UUID.randomUUID();
        String code = "ms-" + world.salt.toLowerCase();
        String rootPath = "/" + code + "/";
        String email = "admin@" + code + ".local";
        String password = "other tenant " + world.salt;
        TenantContext.runWith(tenantId, rootPath, () -> transactions.executeWithoutResult(status -> {
            jdbc.sql("""
                            INSERT INTO tenant (id, code, name, status)
                            VALUES (:id, :code, :name, 'ACTIVE')
                            """)
                    .param("id", tenantId).param("code", code).param("name", "Other " + code)
                    .update();
            UUID unitId = UUID.randomUUID();
            jdbc.sql("""
                            INSERT INTO org_unit (id, tenant_id, parent_id, slug, path, name, unit_type)
                            VALUES (:id, :tenantId, NULL, :slug, :path, 'Head Office', 'HQ')
                            """)
                    .param("id", unitId).param("tenantId", tenantId).param("slug", code)
                    .param("path", rootPath).update();
            UUID userId = provisionUser(tenantId, unitId, email, password, "admin",
                    allPermissionCodes());
            assertThat(userId).isNotNull();
        }));
        return signIn(code, email, password);
    }

    private String[] allPermissionCodes() {
        return jdbc.sql("SELECT code FROM permission").query(String.class).list()
                .toArray(String[]::new);
    }

    private UUID provisionUser(UUID tenantId, UUID orgUnitId, String email, String password,
                               String tag, String[] permissions) {
        UUID roleId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        jdbc.sql("INSERT INTO role (id, tenant_id, code, name) VALUES (:id, :tenantId, :code, :name)")
                .param("id", roleId).param("tenantId", tenantId)
                .param("code", "ms-" + tag + "-" + world.salt.toLowerCase())
                .param("name", "Money stock " + tag).update();
        int granted = jdbc.sql("""
                        INSERT INTO role_permission (tenant_id, role_id, permission_id)
                        SELECT :tenantId, :roleId, id FROM permission WHERE code IN (:codes)
                        """)
                .param("tenantId", tenantId).param("roleId", roleId)
                .param("codes", Arrays.asList(permissions)).update();
        assertThat(granted).as("every permission named exists").isEqualTo(permissions.length);
        jdbc.sql("""
                        INSERT INTO app_user (id, tenant_id, org_unit_id, email, password_hash,
                                              full_name, status)
                        VALUES (:id, :tenantId, :orgUnitId, :email, :hash, :name, 'ACTIVE')
                        """)
                .param("id", userId).param("tenantId", tenantId).param("orgUnitId", orgUnitId)
                .param("email", email).param("hash", passwordEncoder.encode(password))
                .param("name", "Money stock " + tag).update();
        jdbc.sql("INSERT INTO user_role (tenant_id, user_id, role_id) VALUES (:t, :u, :r)")
                .param("t", tenantId).param("u", userId).param("r", roleId).update();
        return userId;
    }

    AuthResult signIn(String tenantCode, String email, String password) {
        return world.client(null, AuthenticationApi.class).login(new LoginRequest()
                .tenantCode(tenantCode).email(email).password(password));
    }

    <T extends ApiClient.Api> T as(AuthResult who, Class<T> type) {
        return world.client(who.getAccessToken(), type);
    }

    private UUID demoTenantId() {
        return jdbc.sql("SELECT resolve_tenant_by_code(:code)")
                .param("code", HttpWorld.DEMO_TENANT).query(UUID.class).single();
    }

    // -------------------------------------------------------------- ledger

    /**
     * A stock movement written straight to the ledger and dated in the past.
     * Services stamp movements with the current time, and storage billing is
     * about what was held last month, which no HTTP call can produce. The
     * rows are the scenario's own (its salted warehouse and SKU).
     */
    void ledgerMovement(UUID warehouseId, UUID skuId, int quantity, OffsetDateTime at) {
        UUID tenantId = demoTenantId();
        TenantContext.runWith(tenantId, null, () -> transactions.executeWithoutResult(status ->
                jdbc.sql("""
                                INSERT INTO stock_movement (id, tenant_id, warehouse_id, movement_type,
                                                            location_type, location_id, sku_id, lot_no,
                                                            quantity, actor, occurred_at, version)
                                VALUES (gen_random_uuid(), :tenantId, :warehouseId, 'ADJUST',
                                        'DOCK', :warehouseId, :skuId, '', :quantity,
                                        'e2e money stock', :at, 0)
                                """)
                        .param("tenantId", tenantId).param("warehouseId", warehouseId)
                        .param("skuId", skuId).param("quantity", quantity)
                        .param("at", at.withOffsetSameInstant(ZoneOffset.UTC)).update()));
    }

    // ------------------------------------------------------------ problems

    private static final Pattern CODE = Pattern.compile("\"code\"\\s*:\\s*\"([^\"]+)\"");

    /** The refusal a call raises; fails when it did not raise one. */
    static FeignException refusal(ThrowingCallable call) {
        Throwable thrown = catchThrowable(call);
        assertThat(thrown).as("the call should have been refused").isInstanceOf(FeignException.class);
        return (FeignException) thrown;
    }

    /** The call is refused with this status, as a problem document carrying this code. */
    static void assertProblem(ThrowingCallable call, int status, String code) {
        FeignException refused = refusal(call);
        assertThat(refused.status()).as(refused.contentUTF8()).isEqualTo(status);
        assertThat(refused.responseHeaders().entrySet().stream()
                .filter(h -> "content-type".equalsIgnoreCase(h.getKey()))
                .flatMap(h -> h.getValue().stream()))
                .as("a problem document").anyMatch(type -> type.contains("problem+json"));
        var matcher = CODE.matcher(refused.contentUTF8());
        assertThat(matcher.find()).as("the problem carries a code: " + refused.contentUTF8()).isTrue();
        assertThat(matcher.group(1)).isEqualTo(code);
    }
}
