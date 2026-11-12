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
package com.lms.shared.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

/**
 * An {@code ETag} on a read, and {@code 304 Not Modified} when the caller
 * already holds that version.
 *
 * <p>For reads a console keeps in its own cache (DOCS/adr/0007): revalidating
 * costs the one query that decides the tag, not the serialising and sending of
 * the body. The tag is a digest of whatever the caller names as the identity of
 * the content, normally each row's id and version.
 *
 * <p>{@code Cache-Control: private, no-cache} so that a shared cache never
 * keeps a tenant's answer and a browser always revalidates.
 */
public final class ConditionalGet {

    private ConditionalGet() {
    }

    /** A strong tag from the parts that identify the content. */
    public static String tag(Iterable<?> identity) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Object part : identity) {
                digest.update(String.valueOf(part).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return "\"" + Base64.getUrlEncoder().withoutPadding().encodeToString(digest.digest()).substring(0, 22)
                    + "\"";
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is a required JDK algorithm", e);
        }
    }

    /** 304 when {@code ifNoneMatch} lists {@code etag}, otherwise 200 with the body and the tag. */
    public static <T> ResponseEntity<T> respond(String ifNoneMatch, String etag, T body) {
        ResponseEntity.BodyBuilder builder = matches(ifNoneMatch, etag)
                ? ResponseEntity.status(304) : ResponseEntity.ok();
        builder.cacheControl(CacheControl.noCache().cachePrivate()).header(HttpHeaders.ETAG, etag);
        return matches(ifNoneMatch, etag) ? builder.build() : builder.body(body);
    }

    static boolean matches(String ifNoneMatch, String etag) {
        if (ifNoneMatch == null || ifNoneMatch.isBlank()) {
            return false;
        }
        for (String candidate : ifNoneMatch.split(",")) {
            String c = candidate.strip();
            if (c.equals("*") || c.equals(etag) || c.equals("W/" + etag)) {
                return true;
            }
        }
        return false;
    }
}
