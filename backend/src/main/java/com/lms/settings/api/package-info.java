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
 * What other modules may use of settings: the port that answers effective
 * values ({@link com.lms.settings.api.Settings}), the definitions a module
 * registers its keys with ({@link com.lms.settings.api.SettingContributor}),
 * and the value types both need. Nothing here exposes a profile or its table.
 */
@org.springframework.modulith.NamedInterface("api")
package com.lms.settings.api;
