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
package com.lms.identity.security;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT signing configuration.
 *
 * @param publicKey       RS256 public key in PEM form. Blank in development,
 *                        where an ephemeral keypair is generated instead.
 * @param privateKey      RS256 private key in PKCS#8 PEM form.
 * @param accessTokenTtl  lifetime of an access token
 * @param refreshTokenTtl lifetime of a refresh token. Long by design: vision
 *                        document 3.1 requires drivers in low-connectivity areas
 *                        to stay authenticated, which is why refresh tokens are
 *                        also bound to a device fingerprint.
 */
@ConfigurationProperties(prefix = "lms.security")
public record SecurityProperties(
        String publicKey,
        String privateKey,
        Duration accessTokenTtl,
        Duration refreshTokenTtl) {

    public SecurityProperties {
        accessTokenTtl = accessTokenTtl == null ? Duration.ofHours(1) : accessTokenTtl;
        refreshTokenTtl = refreshTokenTtl == null ? Duration.ofDays(30) : refreshTokenTtl;
    }

    /** True when no keypair was supplied and an ephemeral one must be generated. */
    public boolean hasConfiguredKeypair() {
        return publicKey != null && !publicKey.isBlank()
                && privateKey != null && !privateKey.isBlank();
    }
}
