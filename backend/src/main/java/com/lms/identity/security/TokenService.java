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

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import com.lms.identity.command.domain.AppUser;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/**
 * Mints access tokens and opaque refresh tokens.
 *
 * <p>Access tokens carry the claims the rest of the platform depends on:
 * {@code tenant} (which the tenant filter turns into the row-level-security
 * scope), {@code org} (the materialised path that bounds visibility), and
 * {@code perms}.
 *
 * <p>Refresh tokens are deliberately <em>not</em> JWTs. They must be revocable,
 * and a self-contained token cannot be revoked without a lookup -- at which
 * point the self-containment buys nothing. They are random opaque strings, and
 * only their SHA-256 hash is stored, so a database disclosure does not hand an
 * attacker usable credentials.
 */
@Service
public class TokenService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ISSUER = "lms-platform";

    private final JwtEncoder encoder;
    private final SecurityProperties properties;

    public TokenService(JwtEncoder encoder, SecurityProperties properties) {
        this.encoder = encoder;
        this.properties = properties;
    }

    /**
     * Issues a signed access token.
     *
     * @param orgPath     materialised path bounding what the user may see
     * @param permissions permission codes granted through the user's roles
     */
    public IssuedToken issueAccessToken(AppUser user, String orgPath, List<String> permissions) {
        Instant now = Instant.now();
        Instant expiry = now.plus(properties.accessTokenTtl());

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .issuedAt(now)
                .expiresAt(expiry)
                .subject(user.id().toString())
                .claim("tenant", user.tenantId().toString())
                .claim("org", orgPath)
                .claim("email", user.email())
                .claim("name", user.fullName())
                .claim("perms", permissions)
                .build();

        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();
        String value = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();

        return new IssuedToken(value, expiry);
    }

    /**
     * Generates an opaque refresh token and the hash to persist alongside it.
     *
     * <p>The raw value is returned to the caller once and never stored.
     */
    public RefreshToken issueRefreshToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return new RefreshToken(raw, hash(raw), Instant.now().plus(properties.refreshTokenTtl()));
    }

    /** SHA-256, hex encoded. Used to look a presented refresh token up by hash. */
    public String hash(String rawToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(rawToken.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    public record IssuedToken(String value, Instant expiresAt) {
    }

    public record RefreshToken(String rawValue, String hash, Instant expiresAt) {
    }
}
