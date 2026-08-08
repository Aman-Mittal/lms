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
 */package com.lms.web;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.testcontainers.containers.PostgreSQLContainer;
import org.yaml.snakeyaml.Yaml;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The hand-authored contract and the running application say the same thing.
 *
 * <p>{@code api/openapi.yaml} is the source the console's client is generated
 * from, and a hand-authored spec drifts silently: an endpoint added without a
 * spec entry is invisible to every client, and a spec entry with no endpoint
 * behind it generates a client method that answers 404. Neither shows up
 * anywhere else, because both halves work perfectly on their own.
 *
 * <p>Compared at path and method, which is what a client's shape is generated
 * from. Schemas are not compared: doing so would mean writing an OpenAPI
 * schema generator here in order to check its own output, and the drift that
 * actually happens is a forgotten endpoint rather than a mistyped field.
 */
@SpringBootTest
class OpenApiContractTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.7-alpine")
            .withDatabaseName("lms").withUsername("lms").withPassword("lms");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    /**
     * Paths the application serves that the contract deliberately omits.
     *
     * <p>Actuator is operational surface for Render's health probe, not API
     * anybody generates a client from. Listed rather than filtered by prefix so
     * the exemption is something a reader can see and disagree with.
     */
    private static final Set<String> NOT_IN_THE_CONTRACT = Set.of("/actuator", "/error");

    /**
     * Qualified by name: Actuator contributes a second
     * {@code RequestMappingHandlerMapping} for its own endpoints, and picking
     * either by type would be picking whichever the container happened to
     * offer.
     */
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Test
    @DisplayName("every endpoint the application serves is in the contract, and the reverse")
    void contractMatchesTheApplication() throws Exception {
        Set<String> served = servedOperations();
        Set<String> documented = documentedOperations();

        Set<String> undocumented = new TreeSet<>(served);
        undocumented.removeAll(documented);

        Set<String> phantom = new TreeSet<>(documented);
        phantom.removeAll(served);

        assertThat(undocumented)
                .as("served but absent from api/openapi.yaml -- invisible to every "
                        + "generated client")
                .isEmpty();

        assertThat(phantom)
                .as("in api/openapi.yaml with nothing behind it -- a generated client "
                        + "method that answers 404")
                .isEmpty();
    }

    /** Every {@code METHOD /path} the dispatcher will route, bar the exemptions. */
    private Set<String> servedOperations() {
        Set<String> operations = new LinkedHashSet<>();

        for (Map.Entry<RequestMappingInfo, ?> mapping
                : handlerMapping.getHandlerMethods().entrySet()) {

            RequestMappingInfo info = mapping.getKey();
            Set<String> patterns = info.getPathPatternsCondition() == null
                    ? Set.of()
                    : info.getPathPatternsCondition().getPatternValues();

            for (String pattern : patterns) {
                if (NOT_IN_THE_CONTRACT.stream().anyMatch(pattern::startsWith)) {
                    continue;
                }
                if (info.getMethodsCondition().getMethods().isEmpty()) {
                    // A mapping with no method restriction answers all of them.
                    // There are none, and one appearing should be a decision
                    // rather than a silent widening of the contract.
                    operations.add("ANY " + pattern);
                    continue;
                }
                info.getMethodsCondition().getMethods()
                        .forEach(method -> operations.add(method.name() + " " + pattern));
            }
        }
        return operations;
    }

    /** Every {@code METHOD /path} the contract declares. */
    @SuppressWarnings("unchecked")
    private Set<String> documentedOperations() throws Exception {
        Path spec = Path.of("..", "api", "openapi.yaml");
        assertThat(Files.exists(spec))
                .as("api/openapi.yaml is the contract clients are generated from")
                .isTrue();

        Map<String, Object> document;
        try (InputStream in = Files.newInputStream(spec)) {
            document = new Yaml().load(in);
        }

        Map<String, Map<String, Object>> paths =
                (Map<String, Map<String, Object>>) document.get("paths");

        Set<String> operations = new LinkedHashSet<>();
        List<String> httpMethods = List.of("get", "put", "post", "delete", "patch", "head",
                "options", "trace");

        paths.forEach((path, byMethod) -> byMethod.keySet().stream()
                .filter(httpMethods::contains)
                .sorted(Comparator.naturalOrder())
                .forEach(method -> operations.add(
                        method.toUpperCase(Locale.ROOT) + " " + path)));

        return operations;
    }
}
