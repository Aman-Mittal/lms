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
package com.lms.smoke;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs {@code deploy/smoke.sh} against the application, exactly as the native
 * image workflow and the deployed-environment workflow run it.
 *
 * <p>The point is to test the script as much as the application. A smoke
 * check that only ever runs against production is a check nobody notices has
 * rotted until the day it is needed; running it here means a renamed endpoint
 * or a changed sign-in fails the ordinary build, where it is cheap to fix.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SmokeScriptTest {

    private static final String DEMO_PASSWORD = "smoke test demo password";

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.7-alpine")
            .withDatabaseName("lms").withUsername("lms").withPassword("lms");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("lms.demo.enabled", () -> "true");
        registry.add("lms.demo.admin-password", () -> DEMO_PASSWORD);
    }

    @LocalServerPort
    private int port;

    @Test
    @DisplayName("the deployment smoke script passes against a freshly started application")
    void smokeScriptPasses() throws IOException, InterruptedException {
        Path script = Path.of("..", "deploy", "smoke.sh").toAbsolutePath().normalize();
        // Run through bash rather than relied on as executable: the file mode
        // is not something every way of committing to this repository keeps.
        assertThat(Files.isRegularFile(script)).as("%s must exist", script).isTrue();

        ProcessBuilder builder = new ProcessBuilder("bash", script.toString())
                .redirectErrorStream(true);
        builder.environment().put("BASE_URL", "http://localhost:" + port);
        builder.environment().put("DEMO_ADMIN_PASSWORD", DEMO_PASSWORD);
        builder.environment().put("READY_TIMEOUT_SECONDS", "30");

        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean finished = process.waitFor(2, TimeUnit.MINUTES);

        assertThat(finished).as("smoke script timed out:%n%s", output).isTrue();
        assertThat(process.exitValue()).as("smoke script failed:%n%s", output).isZero();
        assertThat(output).contains("== Smoke passed");
    }
}
