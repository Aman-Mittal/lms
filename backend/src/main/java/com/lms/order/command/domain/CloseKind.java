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
package com.lms.order.command.domain;

/**
 * How an order, or one of its lines, was closed.
 *
 * <p>Kept beside the closure because the kinds mean different things to
 * whoever reads the order later: a normal close says everything was carried,
 * a short close that the rest will not be, a forced one that someone with the
 * authority ended it whatever its freight was doing.
 */
public enum CloseKind {
    /** Every line was planned and its freight delivered. */
    NORMAL,
    /** The unplanned remainder will not be carried; freight already cut carries on. */
    SHORT,
    /** Closed regardless of the freight, by someone holding ORDER_FORCE_CLOSE. */
    FORCE,
    /** Closed by the platform: the remainder was within the tenant's tolerance. */
    AUTO;

    /** Whether a person closing this way must say why. */
    public boolean needsReason() {
        return this == SHORT || this == FORCE;
    }
}
