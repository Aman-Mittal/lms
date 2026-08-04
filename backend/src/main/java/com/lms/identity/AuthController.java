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
package com.lms.identity;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authentication endpoints.
 *
 * <p>Login takes a tenant code as well as credentials. That is not an
 * afterthought: email addresses are unique per tenant rather than globally, so
 * the same person may legitimately hold accounts with two different customers
 * of the platform. Requiring the code also means the tenant scope is
 * established before any tenant-scoped table is touched, which keeps the
 * row-level-security escape hatch down to a single narrow lookup (DOCS/adr/0005).
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ResponseEntity<AuthService.AuthResult> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(
                request.tenantCode(), request.email(), request.password(), request.deviceFingerprint()));
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthService.AuthResult> refresh(@Valid @RequestBody RefreshRequest request) {
        return ResponseEntity.ok(authService.refresh(request.refreshToken()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequest request) {
        authService.revoke(request.refreshToken());
        return ResponseEntity.noContent().build();
    }

    public record LoginRequest(
            @NotBlank String tenantCode,
            @NotBlank @Email String email,
            @NotBlank String password,
            /* Optional. Binds a driver's long-lived refresh token to a device. */
            String deviceFingerprint) {
    }

    public record RefreshRequest(@NotBlank String refreshToken) {
    }
}
