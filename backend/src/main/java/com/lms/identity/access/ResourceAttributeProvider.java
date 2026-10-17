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
package com.lms.identity.access;

import java.util.Map;
import java.util.UUID;

/**
 * Describes one kind of record to the access decision point.
 *
 * <p>Implemented by the module that owns the record, so the decision point
 * never reads another module's tables: finance says what a freight bill's
 * amount and vendor are, the control tower says whether a ticket is holding
 * money. The policies then decide what those facts mean.
 *
 * <p>Called inside a read-only transaction the decision point opens, with the
 * caller's tenant scope already bound, so an implementation can simply query.
 *
 * <p>Attribute names become {@code resource.<name>} in policy conditions. An
 * {@code orgUnitId} attribute is special: the decision point adds the unit's
 * materialised path as {@code resource.orgPath}, because organisational units
 * belong to identity and no other module should have to look one up.
 */
public interface ResourceAttributeProvider {

    /** The name policies and {@code @abac.permits} use for this kind of record. */
    String resourceType();

    /**
     * The record's attributes, or an empty map if there is no such record.
     *
     * <p>Empty rather than an exception: the service being guarded reports a
     * missing record as 404 in its own words, and the decision should not
     * pre-empt it with a different error.
     */
    Map<String, Object> attributesOf(UUID id);
}
