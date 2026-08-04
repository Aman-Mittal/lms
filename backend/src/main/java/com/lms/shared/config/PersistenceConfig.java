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

import java.util.List;

import javax.sql.DataSource;

import com.lms.shared.tenant.TenantAwareTransactionManager;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jdbc.core.convert.JdbcCustomConversions;
import org.springframework.data.jdbc.core.dialect.JdbcDialect;
import org.springframework.data.jdbc.core.dialect.JdbcPostgresDialect;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
public class PersistenceConfig {

    /**
     * Pins the SQL dialect so Spring Data never has to detect it.
     *
     * <p>Boot's own {@code jdbcDialect} bean takes a JDBC template and reads
     * database metadata over a live connection. That is impossible during AOT
     * processing, where the bean graph is built at image-build time with no
     * database anywhere, and the native build fails with "Failed to obtain JDBC
     * Connection".
     *
     * <p>The {@code spring.data.jdbc.dialect} property would express the same
     * thing, but it is bound through {@code @ConfigurationProperties} and that
     * binding does not run during AOT -- the same gap that forces
     * {@link DataSourceConfig} to read the {@code Environment} by hand. A plain
     * bean needs no binding, so it works in both phases.
     *
     * <p>The method is deliberately <em>not</em> named {@code jdbcDialect}:
     * Boot's definition is {@code @ConditionalOnMissingBean}, so a
     * differently-named bean of the same type makes it back off cleanly, while
     * an identical name collides during registration instead.
     *
     * <p>The platform targets PostgreSQL exclusively, so there is nothing to
     * detect.
     */
    @Bean
    JdbcDialect lmsJdbcDialect() {
        return JdbcPostgresDialect.INSTANCE;
    }

    /**
     * Registers the {@code jsonb} converters.
     *
     * <p>Named {@code lmsJdbcCustomConversions} rather than
     * {@code jdbcCustomConversions} so Boot's {@code @ConditionalOnMissingBean}
     * definition backs off by type instead of colliding by name.
     */
    @Bean
    JdbcCustomConversions lmsJdbcCustomConversions() {
        return new JdbcCustomConversions(List.of(
                JsonbConverters.PgObjectToJson.INSTANCE,
                JsonbConverters.JsonToPgObject.INSTANCE));
    }

    /**
     * Replaces Spring Boot's default transaction manager so that every
     * transaction publishes its tenant onto the connection for row-level
     * security (DOCS/adr/0005).
     *
     * <p>Because this is the transaction manager rather than an interceptor or
     * an aspect, there is no way to open a transaction that skips it -- which is
     * the whole point. Registering it as a bean means Boot backs off its own.
     */
    @Bean
    @ConditionalOnMissingBean(PlatformTransactionManager.class)
    PlatformTransactionManager transactionManager(DataSource dataSource) {
        return new TenantAwareTransactionManager(dataSource);
    }
}
