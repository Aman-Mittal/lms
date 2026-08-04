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

import java.security.KeyPair;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * RS256 signing and verification keys.
 *
 * <p>Asymmetric rather than HMAC because the vision document (3.1) calls for it
 * and because it lets verification be delegated later -- to an API gateway, or
 * to services split out of this monolith -- by publishing only the public key.
 */
@Configuration(proxyBeanMethods = false)
public class JwtConfig {

    private static final Logger log = LoggerFactory.getLogger(JwtConfig.class);

    private final RSAPublicKey publicKey;
    private final RSAPrivateKey privateKey;

    public JwtConfig(SecurityProperties properties) {
        if (properties.hasConfiguredKeypair()) {
            this.publicKey = RsaKeys.parsePublicKey(properties.publicKey());
            this.privateKey = RsaKeys.parsePrivateKey(properties.privateKey());
        } else {
            // Convenient locally, catastrophic in production: every restart
            // invalidates every issued token, and a horizontally scaled
            // deployment would have instances that cannot verify each other's.
            // Loud rather than silent.
            log.warn("""
                    No JWT keypair configured -- generating an ephemeral one.
                    All issued tokens become invalid when this process restarts.
                    Acceptable for local development ONLY. Set JWT_PUBLIC_KEY and
                    JWT_PRIVATE_KEY for any deployed environment.""");
            KeyPair keyPair = RsaKeys.generateEphemeral();
            this.publicKey = (RSAPublicKey) keyPair.getPublic();
            this.privateKey = (RSAPrivateKey) keyPair.getPrivate();
        }
    }

    @Bean
    JwtEncoder jwtEncoder() {
        RSAKey jwk = new RSAKey.Builder(publicKey).privateKey(privateKey).build();
        JWKSource<SecurityContext> source = new ImmutableJWKSet<>(new JWKSet(jwk));
        return new NimbusJwtEncoder(source);
    }

    @Bean
    JwtDecoder jwtDecoder() {
        return NimbusJwtDecoder.withPublicKey(publicKey).build();
    }
}
