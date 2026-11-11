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

import java.util.Collection;
import java.util.Map;

/**
 * The effective settings of a context, for a module that applies them.
 *
 * <p>Every call joins the caller's transaction (or reads in one of its own
 * when there is none) and never opens a second connection (DOCS/adr/0006).
 * None checks a permission: the caller is the platform applying a rule, or a
 * module whose own endpoint has already been authorised. The tenant is the
 * one in scope.
 */
public interface Settings {

    /** The effective value; never null, because every key has a system default. */
    <T> T effective(SettingKey<T> key, SettingContext context);

    /** The effective value and where it came from. */
    <T> Resolved<T> resolve(SettingKey<T> key, SettingContext context);

    /** Several keys in one read. */
    EffectiveSettings resolveAll(Collection<SettingKey<?>> keys, SettingContext context);

    /**
     * Sets values on the organisation default profile, creating it when the
     * tenant has none; a null value removes the key from it. Validated exactly
     * as the settings API validates, audited on the profile's history, and
     * joins the caller's transaction, which must exist.
     */
    void setOrgDefaults(Map<SettingKey<?>, Object> values);
}
