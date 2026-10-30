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
package com.lms.events.web;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * How many events are parked, across tenants. Parked events do not make the
 * application unhealthy -- it is serving, and they wait for an operator -- so
 * this is UP with a {@code degraded} flag, never DOWN. A count only: no event
 * content leaves through the health endpoint.
 */
@Component("eventDeadLetter")
public class DeadLetterHealthIndicator implements HealthIndicator {

    private final JdbcClient jdbc;

    public DeadLetterHealthIndicator(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Health health() {
        long parked = jdbc.sql("SELECT count_parked_events()").query(Long.class).single();
        return Health.up().withDetail("parkedEvents", parked).withDetail("degraded", parked > 0).build();
    }
}
