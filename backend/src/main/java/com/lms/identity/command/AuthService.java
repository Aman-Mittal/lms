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

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.lms.identity.command.domain.AppUser;
import com.lms.identity.command.domain.OrgUnit;
import com.lms.identity.command.AppUserRepository;
import com.lms.identity.command.OrgUnitRepository;
import com.lms.identity.command.RefreshTokenRepository;
import com.lms.identity.command.TenantRepository;
import com.lms.identity.security.TokenService;
import com.lms.shared.error.AuthenticationFailedException;
import com.lms.shared.tenant.TenantContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Credential verification and token issuance.
 *
 * <p>Every failure path -- unknown tenant, unknown user, wrong password,
 * suspended account -- produces the same {@link AuthenticationFailedException}
 * with the same message. Distinguishing them would let an unauthenticated
 * caller enumerate which tenant codes and email addresses exist.
 *
 * <p>Transactions here are opened explicitly with a {@link TransactionTemplate}
 * rather than declared with {@code @Transactional}, and that is load-bearing.
 * {@code TenantAwareTransactionManager} publishes the tenant scope onto the
 * connection <em>while starting the transaction</em>, so a scope established
 * inside an already-open transaction arrives too late: the connection would
 * still carry the empty scope, row-level security would match nothing, and
 * every login would fail with "invalid credentials" for reasons nowhere near
 * the credentials. Resolving the tenant, then binding the scope, then opening
 * the transaction is the only order that works.
 */
@Service
public class AuthService {

    private static final String GENERIC_FAILURE = "Invalid tenant, email or password";

    private final TenantRepository tenants;
    private final AppUserRepository users;
    private final OrgUnitRepository orgUnits;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final TransactionTemplate transactions;

    public AuthService(TenantRepository tenants,
                       AppUserRepository users,
                       OrgUnitRepository orgUnits,
                       RefreshTokenRepository refreshTokens,
                       PasswordEncoder passwordEncoder,
                       TokenService tokenService,
                       PlatformTransactionManager transactionManager) {
        this.tenants = tenants;
        this.users = users;
        this.orgUnits = orgUnits;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public AuthResult login(String tenantCode, String email, String password, String deviceFingerprint) {
        // Resolved through a SECURITY DEFINER function, which is the one
        // sanctioned way to read a tenant before any scope exists.
        UUID tenantId = tenants.resolveIdByCode(tenantCode)
                .orElseThrow(() -> new AuthenticationFailedException(GENERIC_FAILURE));

        // Scope first, transaction second. Reversing these silently breaks
        // every login -- see the class comment.
        return TenantContext.callWith(tenantId, null, () -> transactions.execute(status -> {
            AppUser user = users.findByTenantAndEmail(tenantId, email)
                    .orElseThrow(() -> new AuthenticationFailedException(GENERIC_FAILURE));

            // Verify the password even for accounts that cannot log in, so that
            // a suspended account does not answer faster than an active one.
            boolean passwordMatches = passwordEncoder.matches(password, user.passwordHash());
            if (!passwordMatches || !user.canAuthenticate()) {
                throw new AuthenticationFailedException(GENERIC_FAILURE);
            }

            return issueFor(user, deviceFingerprint);
        }));
    }

    public AuthResult refresh(String rawRefreshToken) {
        String hash = tokenService.hash(rawRefreshToken);

        // Looked up outside any tenant scope, then used to establish one: the
        // token hash is unguessable and globally unique, so it identifies the
        // tenant rather than presupposing it.
        var stored = refreshTokens.findActiveByHash(hash, Instant.now())
                .orElseThrow(() -> new AuthenticationFailedException("Refresh token is invalid or expired"));

        return TenantContext.callWith(stored.tenantId(), null, () -> transactions.execute(status -> {
            AppUser user = users.findById(stored.userId())
                    .orElseThrow(() -> new AuthenticationFailedException("Refresh token is invalid or expired"));
            if (!user.canAuthenticate()) {
                throw new AuthenticationFailedException("Refresh token is invalid or expired");
            }

            // Rotate: the presented token is revoked and a new one issued, so a
            // stolen token is usable at most once before the legitimate holder's
            // next refresh invalidates it.
            refreshTokens.revoke(stored.id(), Instant.now());
            return issueFor(user, stored.deviceFingerprint());
        }));
    }

    public void revoke(String rawRefreshToken) {
        refreshTokens.findActiveByHash(tokenService.hash(rawRefreshToken), Instant.now())
                .ifPresent(token -> TenantContext.runWith(token.tenantId(), null,
                        () -> transactions.executeWithoutResult(
                                status -> refreshTokens.revoke(token.id(), Instant.now()))));
    }

    private AuthResult issueFor(AppUser user, String deviceFingerprint) {
        String orgPath = orgUnits.findById(user.orgUnitId())
                .map(OrgUnit::path)
                .orElseThrow(() -> new IllegalStateException(
                        "User " + user.id() + " references a missing organisational unit"));

        List<String> permissions = users.findPermissionCodes(user.id());
        TokenService.IssuedToken access = tokenService.issueAccessToken(user, orgPath, permissions);
        TokenService.RefreshToken refresh = tokenService.issueRefreshToken();

        refreshTokens.store(UUID.randomUUID(), user.tenantId(), user.id(), refresh.hash(),
                deviceFingerprint, refresh.expiresAt());
        users.save(user.withLastLoginAt(Instant.now()));

        return new AuthResult(
                access.value(), access.expiresAt(), refresh.rawValue(), refresh.expiresAt(),
                user.id(), user.tenantId(), orgPath, permissions);
    }

    /**
     * @param refreshToken returned in the response body rather than a cookie:
     *                     the console is served from a different origin
     *                     (GitHub Pages) and drivers authenticate from a native
     *                     app, so a cookie would not reach either.
     */
    public record AuthResult(
            String accessToken,
            Instant accessTokenExpiresAt,
            String refreshToken,
            Instant refreshTokenExpiresAt,
            UUID userId,
            UUID tenantId,
            String orgPath,
            List<String> permissions) {

        public String tokenType() {
            return "Bearer";
        }
    }

    /** Present so callers can express "maybe authenticated" without exceptions. */
    public Optional<AppUser> findUser(UUID id) {
        return users.findById(id);
    }
}
