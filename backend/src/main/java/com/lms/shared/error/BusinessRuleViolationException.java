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

/**
 * A domain rule refused the operation: an expired vehicle document blocking
 * dispatch, a load exceeding vehicle capacity, an overlapping terminal polygon,
 * an illegal trip state transition.
 *
 * <p>Distinct from a validation failure. The request was well-formed; the
 * business said no. Surfaces as {@code 409 Conflict}, with {@link #code()}
 * giving clients something stable to branch on rather than parsing prose.
 */
public class BusinessRuleViolationException extends RuntimeException {

    private final String code;

    public BusinessRuleViolationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
