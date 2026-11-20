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
package com.lms.identity.command;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import com.lms.shared.error.BusinessRuleViolationException;

/**
 * What a password an administrator types for somebody else must satisfy.
 *
 * <p>Sign-in checks nothing about a password beyond the hash, so these are the
 * only rules there are. They are the ones that matter when a person, not the
 * user, chooses the secret: long enough not to be guessed at, short enough for
 * the hash to read all of it (bcrypt stops at 72 bytes), not a single repeated
 * character, not the login id, and mixing letters with digits. A violation is a
 * {@code password-policy} refusal whose message says which rule.
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 12;
    /** bcrypt reads at most this many bytes; past it a longer password would silently equal a shorter one. */
    public static final int MAX_BYTES = 72;

    private PasswordPolicy() {
    }

    public static void require(String password, String loginId) {
        if (password == null || password.length() < MIN_LENGTH) {
            throw refused("The password must be at least " + MIN_LENGTH + " characters long");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw refused("The password must be at most " + MAX_BYTES + " bytes long");
        }
        if (password.chars().distinct().count() < 4) {
            throw refused("The password must not be a repeated pattern");
        }
        if (password.chars().noneMatch(Character::isLetter) || password.chars().noneMatch(Character::isDigit)) {
            throw refused("The password must contain both letters and digits");
        }
        if (loginId != null && !loginId.isBlank()) {
            String local = loginId.strip().toLowerCase(Locale.ROOT);
            int at = local.indexOf('@');
            if (at > 0) {
                local = local.substring(0, at);
            }
            if (local.length() >= 4 && password.toLowerCase(Locale.ROOT).contains(local)) {
                throw refused("The password must not contain the login id");
            }
        }
    }

    private static BusinessRuleViolationException refused(String why) {
        return new BusinessRuleViolationException("password-policy", why);
    }
}
