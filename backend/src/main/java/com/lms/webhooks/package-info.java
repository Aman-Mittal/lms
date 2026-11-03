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

/**
 * Outbound webhooks.
 *
 * <p>A tenant subscribes an address to event types; each event of a subscribed
 * type becomes one delivery row (the outbox) and a timer posts it, signed,
 * retrying on a backoff until it gives up. A subscription that keeps failing is
 * paused by the platform and says why.
 *
 * <p>Depends only on other modules' published events, never on their types.
 * The listener's one job is the outbox write: a receiver that is down cannot
 * fail or hold up the business change that raised the event, and an event the
 * listener has already recorded is not recorded twice when the publication
 * registry republishes it.
 *
 * <p>Settings, each overridable from the environment (for example
 * {@code LMS_WEBHOOKS_DISPATCHER_ENABLED}): {@code lms.webhooks.dispatcher.enabled}
 * (true), {@code .dispatcher.interval-ms} (5000), {@code .batch-size} (50),
 * {@code .initial-backoff} (PT30S), {@code .max-backoff} (PT1H),
 * {@code .max-attempts} (8), {@code .pause-after-failures} (5),
 * {@code .connect-timeout} (PT5S), {@code .read-timeout} (PT10S),
 * {@code .allow-http} (false) and {@code .allow-private-targets} (false). The
 * last two exist for local development and tests only.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Webhooks",
        allowedDependencies = {"order::events", "planning::events", "execution::events",
                "delivery::events"})
package com.lms.webhooks;
