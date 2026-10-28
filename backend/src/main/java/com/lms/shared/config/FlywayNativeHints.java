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

import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;

/**
 * Reflection hints Flyway needs, in a native image, to report why it could not
 * connect.
 *
 * <p>When opening the first connection fails, Flyway looks for a more specific
 * exception by reflection: it calls {@code isFlywaySpecificVersionOf(SQLException)}
 * on, and then constructs, each of the classes below. Unregistered, the lookup
 * throws {@code NoSuchMethodException} from inside the error handler, and the
 * real {@code SQLException} -- wrong password, SSL required, host unreachable --
 * is lost. The service then fails to start with a message that names a Flyway
 * class and says nothing about the database.
 */
@Configuration(proxyBeanMethods = false)
@ImportRuntimeHints(FlywayNativeHints.Registrar.class)
public class FlywayNativeHints {

    static final String[] SPECIFIC_EXCEPTIONS = {
            "org.flywaydb.core.internal.exception.sqlExceptions.FlywaySqlServerUntrustedCertificateSqlException",
            "org.flywaydb.core.internal.exception.sqlExceptions.FlywaySqlNoIntegratedAuthException",
            "org.flywaydb.core.internal.exception.sqlExceptions.FlywaySqlNoDriversForInteractiveAuthException"
    };

    static class Registrar implements RuntimeHintsRegistrar {

        @Override
        public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
            for (String type : SPECIFIC_EXCEPTIONS) {
                hints.reflection().registerType(TypeReference.of(type),
                        MemberCategory.INVOKE_PUBLIC_METHODS,
                        MemberCategory.INVOKE_DECLARED_CONSTRUCTORS);
            }
        }
    }
}
