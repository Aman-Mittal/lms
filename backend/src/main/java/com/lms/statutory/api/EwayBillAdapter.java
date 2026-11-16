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
package com.lms.statutory.api;

/**
 * The port to a statutory e-way bill system. One bean per implementation; a
 * unit picks one by {@link #code()} through the setting
 * {@code statutory.ewayBill.adapter} (NONE, the default, means none).
 *
 * <p>An implementation must be safe to call from a worker thread with no
 * tenant scope, hold no database transaction, and finish within the timeout
 * the caller sets: the caller abandons a call that does not, and records it as
 * a failure. It never receives or returns a secret.
 */
public interface EwayBillAdapter {

    /** The value of the adapter setting that selects this implementation, upper case. */
    String code();

    /** Performs the action; refusals are an answer, transport failures are exceptions. */
    EwayBillResponse execute(EwayBillRequest request);
}
