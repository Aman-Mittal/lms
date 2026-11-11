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
package com.lms.settings.api;

import java.util.List;
import java.util.Optional;

/**
 * Implemented by a module that owns settings, as a bean: the registry reads
 * every contributor's definitions once, at start-up, and refuses to start on
 * a key defined twice or a default its own definition would refuse.
 */
public interface SettingContributor {

    List<SettingDefinition> definitions();

    /**
     * A further check on a value submitted for one of this contributor's keys,
     * after the definition's own rules accepted it: a rule only the owning
     * module can judge (a name that must exist, a flag it keeps fixed). Called
     * on writes only, in the writer's transaction, with the canonical value;
     * a stored value is never run through it again.
     *
     * @return why the value is refused, in words a person can act on, or empty
     */
    default Optional<String> refusal(SettingDefinition definition, Object canonicalValue) {
        return Optional.empty();
    }
}
