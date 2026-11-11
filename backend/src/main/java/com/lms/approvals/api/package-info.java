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
 * The synchronous surface of the approvals module, for the modules that own
 * what is being approved.
 *
 * <p>Raising a request ({@link com.lms.approvals.api.ApprovalRequests}) and
 * being told of its outcome ({@link com.lms.approvals.api.ApprovalOutcomeHandler})
 * are synchronous on purpose: the caller must be able to answer "awaiting
 * approval" in the same response, and a handler must be able to refuse a
 * decision that no longer makes sense, which an after-commit event cannot do.
 */
@org.springframework.modulith.NamedInterface("api")
package com.lms.approvals.api;
