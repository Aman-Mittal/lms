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
package com.lms.identity.query;

import java.util.UUID;

/**
 * One organisational unit, as the administration screen shows it.
 *
 * @param level            the unit type's rank: 0 for HQ down to 5 for a hub
 * @param countryCode      a COUNTRY unit's own ISO 3166-1 alpha-2 code
 * @param stateCode        the unit's own state code, if it carries one
 * @param effectiveCountry the nearest country at or above the unit
 * @param effectiveState   the nearest state at or above the unit and beneath
 *                         that country
 * @param version          the optimistic-lock version, also the {@code ETag}
 */
public record OrgUnitView(UUID id, UUID parentId, String slug, String path, String name, String unitType,
                          int level, String countryCode, String stateCode, String effectiveCountry,
                          String effectiveState, long version) {
}
