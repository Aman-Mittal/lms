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
package com.lms.web;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.lms.acceptance.TestFixtures;
import com.lms.identity.command.domain.AppUser;
import com.lms.identity.security.TokenService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Raw HTTP against the running application, for tests that care about the
 * exact status and problem document rather than about a generated client's
 * view of them -- two tenants, bearer tokens minted with the application's own
 * key, and a start-gate for firing the same request from many threads at once.
 */
public final class ApiTestKit {

    /** One provisioned tenant and a caller inside it. */
    public record Tenant(UUID tenantId, UUID orgUnitId, String orgPath, AppUser user) {
    }

    /** What a request got back, and how long it took. */
    public record Outcome(int status, String body, long nanos) {

        public String code() {
            try {
                JsonNode node = new ObjectMapper().readTree(body);
                return node.has("code") ? node.get("code").asString() : null;
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    private final TestFixtures fixtures;
    private final TokenService tokens;
    private final int port;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    public ApiTestKit(TestFixtures fixtures, TokenService tokens, int port) {
        this.fixtures = fixtures;
        this.tokens = tokens;
        this.port = port;
    }

    public Tenant newTenant(String prefix) {
        String salt = UUID.randomUUID().toString().substring(0, 8);
        String orgPath = "/" + prefix + "-" + salt + "/";
        UUID tenantId = fixtures.createTenant(prefix + "-" + salt, prefix + " " + salt);
        UUID orgUnitId = fixtures.createOrgUnit(tenantId, orgPath, prefix + " HQ", "HQ", null);
        AppUser user = new AppUser(UUID.randomUUID(), tenantId, orgUnitId,
                "ops@" + prefix + salt + ".test", "not-used", "Ops", AppUser.UserStatus.ACTIVE,
                null, 0, null, null, null, Instant.now(), Instant.now());
        return new Tenant(tenantId, orgUnitId, orgPath, user);
    }

    /** A caller inside a tenant that something else already provisioned. */
    public Tenant tenantOf(UUID tenantId, UUID orgUnitId, String orgPath) {
        AppUser user = new AppUser(UUID.randomUUID(), tenantId, orgUnitId,
                "ops@" + tenantId + ".test", "not-used", "Ops", AppUser.UserStatus.ACTIVE,
                null, 0, null, null, null, Instant.now(), Instant.now());
        return new Tenant(tenantId, orgUnitId, orgPath, user);
    }

    public String token(Tenant tenant, String... permissions) {
        return tokens.issueAccessToken(tenant.user(), tenant.orgPath(), List.of(permissions)).value();
    }

    public Outcome get(Tenant tenant, String path, String... permissions) {
        return send(HttpRequest.newBuilder(uri(path))
                .header("Authorization", "Bearer " + token(tenant, permissions)).GET().build());
    }

    public Outcome post(Tenant tenant, String path, String body, String... permissions) {
        return send(HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token(tenant, permissions))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build());
    }

    public Outcome put(Tenant tenant, String path, String body, String... permissions) {
        return send(HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token(tenant, permissions))
                .PUT(HttpRequest.BodyPublishers.ofString(body)).build());
    }

    public JsonNode parse(Outcome outcome) {
        return json.readTree(outcome.body());
    }

    public Outcome send(HttpRequest request) {
        long started = System.nanoTime();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            return new Outcome(response.statusCode(), response.body(), System.nanoTime() - started);
        } catch (IOException e) {
            throw new AssertionError("The request never reached the server", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted waiting for a response", e);
        }
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    /**
     * Runs every call at the same moment: all threads are created and parked on
     * a latch, then released together, so the requests genuinely overlap.
     * Bounded, so a deadlock fails the test rather than hanging the build.
     */
    public static <T> List<T> race(List<Callable<T>> calls, Duration limit) {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        CountDownLatch ready = new CountDownLatch(calls.size());
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> call : calls) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return call.call();
                }));
            }
            ready.await();
            go.countDown();
            List<T> results = new ArrayList<>();
            long deadline = System.nanoTime() + limit.toNanos();
            for (Future<T> future : futures) {
                results.add(future.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
            }
            return results;
        } catch (TimeoutException e) {
            throw new AssertionError("Concurrent requests did not finish within " + limit
                    + " -- a deadlock or a hang", e);
        } catch (Exception e) {
            throw new AssertionError("A concurrent request failed", e);
        } finally {
            pool.shutdownNow();
        }
    }
}
