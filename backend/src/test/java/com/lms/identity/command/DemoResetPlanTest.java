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
package com.lms.identity.command;

import java.util.List;

import com.lms.identity.command.DemoResetPlan.Reference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The order a demo reset clears tables in, without a database. */
class DemoResetPlanTest {

    @Test
    @DisplayName("a table is cleared before every table it references")
    void childrenFirst() {
        List<String> order = DemoResetPlan.deletionOrder(
                List.of("org_unit", "app_user", "user_role", "role"),
                List.of(new Reference("app_user", "org_unit"),
                        new Reference("user_role", "app_user"),
                        new Reference("user_role", "role")));

        assertThat(order).containsExactlyInAnyOrder("org_unit", "app_user", "user_role", "role");
        assertThat(order.indexOf("user_role")).isLessThan(order.indexOf("app_user"));
        assertThat(order.indexOf("user_role")).isLessThan(order.indexOf("role"));
        assertThat(order.indexOf("app_user")).isLessThan(order.indexOf("org_unit"));
    }

    @Test
    @DisplayName("a self-reference and a reference to the tenant row need no order")
    void selfAndTenantReferencesIgnored() {
        List<String> order = DemoResetPlan.deletionOrder(
                List.of("org_unit"),
                List.of(new Reference("org_unit", "org_unit"), new Reference("org_unit", "tenant")));

        assertThat(order).containsExactly("org_unit");
    }

    @Test
    @DisplayName("a cycle between tables is refused rather than half cleared")
    void cycleRefused() {
        assertThatThrownBy(() -> DemoResetPlan.deletionOrder(
                List.of("a", "b"),
                List.of(new Reference("a", "b"), new Reference("b", "a"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cycle");
    }

    @Test
    @DisplayName("a table without tenant_id that references tenant data is refused, since it could not be cleared")
    void unclearableReferenceRefused() {
        assertThatThrownBy(() -> DemoResetPlan.deletionOrder(
                List.of("consignment"),
                List.of(new Reference("global_note", "consignment"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("global_note -> consignment");
    }

    @Test
    @DisplayName("references between tables outside the reset are no concern of it")
    void unrelatedReferencesIgnored() {
        List<String> order = DemoResetPlan.deletionOrder(
                List.of("claim"),
                List.of(new Reference("event_publication_archive", "event_publication")));

        assertThat(order).containsExactly("claim");
    }
}
