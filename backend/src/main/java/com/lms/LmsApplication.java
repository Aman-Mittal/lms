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
package com.lms;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.modulith.Modulithic;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the Logistics Management Platform.
 *
 * <p>The platform is a modular monolith: each bounded context lives in its own
 * top-level package under {@code com.lms} and communicates with its peers only
 * via application events. {@code ModularityTests} fails the build if that
 * boundary is crossed directly.
 *
 * <p>Scheduling is enabled in-process because Render's free tier offers no
 * background workers or cron jobs -- every periodic concern (allocation
 * timeouts, telematics retention) runs inside this web service.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@Modulithic(
        systemName = "LMS Platform",
        // `shared` holds cross-cutting infrastructure (geometry, tenant context,
        // error model). Every context may depend on it, so it is declared shared
        // rather than being flagged as a boundary violation by every module.
        sharedModules = "shared")
@EnableScheduling
public class LmsApplication {

    public static void main(String[] args) {
        SpringApplication.run(LmsApplication.class, args);
    }
}
