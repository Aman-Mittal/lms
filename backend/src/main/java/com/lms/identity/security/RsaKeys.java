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

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * PEM parsing and keypair generation for RS256 tokens.
 *
 * <p>Hand-rolled against {@code java.security} rather than pulling in
 * BouncyCastle: the work is a Base64 decode and two key specs, and every
 * avoided dependency is one less thing to license-audit and one less thing to
 * configure for GraalVM.
 */
public final class RsaKeys {

    private RsaKeys() {
    }

    /** Parses an X.509 {@code BEGIN PUBLIC KEY} PEM block. */
    public static RSAPublicKey parsePublicKey(String pem) {
        byte[] der = decodePem(pem, "PUBLIC KEY");
        try {
            return (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(der));
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalArgumentException(
                    "JWT_PUBLIC_KEY is not a valid X.509 RSA public key in PEM form", e);
        }
    }

    /**
     * Parses a PKCS#8 {@code BEGIN PRIVATE KEY} PEM block.
     *
     * <p>PKCS#1 ({@code BEGIN RSA PRIVATE KEY}) is rejected with a message
     * saying how to convert, because the two look nearly identical and the
     * resulting failure is otherwise cryptic.
     */
    public static RSAPrivateKey parsePrivateKey(String pem) {
        if (pem != null && pem.contains("BEGIN RSA PRIVATE KEY")) {
            throw new IllegalArgumentException(
                    "JWT_PRIVATE_KEY is in PKCS#1 format; PKCS#8 is required. Convert it with: "
                            + "openssl pkcs8 -topk8 -nocrypt -in key.pem -out key-pkcs8.pem");
        }
        byte[] der = decodePem(pem, "PRIVATE KEY");
        try {
            return (RSAPrivateKey) KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalArgumentException(
                    "JWT_PRIVATE_KEY is not a valid PKCS#8 RSA private key in PEM form", e);
        }
    }

    /**
     * Generates a throwaway 2048-bit keypair.
     *
     * <p>For local development only. Tokens signed with it stop verifying the
     * moment the process restarts, which is the desired behaviour locally and
     * would be a serious defect in production -- hence the loud warning at the
     * call site in {@link JwtConfig}.
     */
    public static KeyPair generateEphemeral() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("RSA key generation is unavailable", e);
        }
    }

    private static byte[] decodePem(String pem, String type) {
        if (pem == null || pem.isBlank()) {
            throw new IllegalArgumentException("PEM content for " + type + " is empty");
        }
        String normalised = pem
                .replace("-----BEGIN " + type + "-----", "")
                .replace("-----END " + type + "-----", "")
                .replaceAll("\\s", "");
        try {
            return Base64.getDecoder().decode(normalised);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "PEM content for " + type + " is not valid Base64. Check that the full "
                            + "BEGIN/END block was supplied and newlines were preserved.", e);
        }
    }
}
