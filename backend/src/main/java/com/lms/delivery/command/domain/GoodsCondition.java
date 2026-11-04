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
package com.lms.delivery.command.domain;

/**
 * The state the goods were received in, as the proof records it.
 *
 * <p>Anything but CLEAN carries a remark saying what was wrong, and keeps the
 * carrier's trip from settling until somebody has looked at it.
 */
public enum GoodsCondition {
    /** Received whole and undamaged. */
    CLEAN,
    /** Less arrived than was sent. */
    SHORTAGE,
    /** Something arrived damaged. */
    DAMAGE
}
