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
package com.lms.shared.error;

import java.util.UUID;

/**
 * A referenced resource does not exist, or is not visible to the caller.
 *
 * <p>Those two cases are deliberately indistinguishable. Under row-level
 * security another tenant's row simply is not there, and a caller must not be
 * able to tell "does not exist" from "exists but belongs to someone else" --
 * that difference is enough to probe for the existence of other tenants' data.
 */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String resourceType, UUID id) {
        super(resourceType + " " + id + " was not found");
    }

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
