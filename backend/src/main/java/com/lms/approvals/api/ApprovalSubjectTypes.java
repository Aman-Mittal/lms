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
package com.lms.approvals.api;

/**
 * The subject types a request can be raised for, one per flow.
 *
 * <p>A name here is a contract: it is stored on every request, filtered on by
 * the inbox and carried by webhooks. Add a type here, give it a default policy
 * in {@code ApprovalPolicyDefaults}, and register its handler in the owning
 * module; the application refuses to start if a handler names a type with no
 * default policy, or a type has no handler.
 */
public final class ApprovalSubjectTypes {

    /** Cancelling a sales order (FR-ORD-006). Owned by {@code order}. */
    public static final String ORDER_CANCELLATION = "ORDER_CANCELLATION";

    /** Reversing a confirmed proof of delivery before billing uses it. Owned by {@code delivery}. */
    public static final String POD_REVERSAL = "POD_REVERSAL";

    private ApprovalSubjectTypes() {
    }
}
