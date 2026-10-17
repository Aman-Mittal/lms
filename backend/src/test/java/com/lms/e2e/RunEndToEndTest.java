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
package com.lms.e2e;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectClasspathResource;
import org.junit.platform.suite.api.Suite;

import static io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME;
import static io.cucumber.junit.platform.engine.Constants.PLUGIN_PROPERTY_NAME;

/**
 * The end-to-end suite: Gherkin driven entirely over HTTP.
 *
 * <p>Separate from {@code RunAcceptanceTest} on purpose. The acceptance suite
 * calls command services in-process, which is what makes it fast and precise
 * about domain rules -- and is also why it cannot notice a controller that
 * maps a field wrongly, a permission missing from a token, or a spec that
 * disagrees with the server. These scenarios go through the generated client
 * and the network stack like any consumer, with nothing in-process but the
 * provisioning of a second user.
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("e2e")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "com.lms.e2e")
@ConfigurationParameter(key = PLUGIN_PROPERTY_NAME,
        value = "pretty, summary, html:target/e2e-report.html, json:target/e2e.json")
public class RunEndToEndTest {
}
