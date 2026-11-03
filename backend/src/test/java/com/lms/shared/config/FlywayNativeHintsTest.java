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
package com.lms.shared.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.TypeReference;

import static org.assertj.core.api.Assertions.assertThat;

class FlywayNativeHintsTest {

    @Test
    @DisplayName("Every exception class Flyway probes by reflection is registered with methods and constructors")
    void registersTheClassesFlywayProbes() {
        RuntimeHints hints = new RuntimeHints();
        new FlywayNativeHints.Registrar().registerHints(hints, getClass().getClassLoader());

        assertThat(FlywayNativeHints.SPECIFIC_EXCEPTIONS).hasSize(3).allSatisfy(type -> {
            var registered = hints.reflection().getTypeHint(TypeReference.of(type));
            assertThat(registered).as(type).isNotNull();
            assertThat(registered.getMemberCategories()).as(type)
                    .contains(MemberCategory.INVOKE_PUBLIC_METHODS, MemberCategory.INVOKE_DECLARED_CONSTRUCTORS);
            // The names must be real classes: a rename in a Flyway upgrade would
            // otherwise leave a hint that silently matches nothing.
            assertThat(Class.forName(type)).as(type).isNotNull();
        });
    }
}
