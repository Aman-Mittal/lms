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
 */package com.lms.masterdata.web;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;

/**
 * A statutory certificate being filed against a vehicle, driver or partner.
 *
 * <p>{@code expiresOn} is nullable and means "never expires" -- a registration
 * document, typically. Not defaulting it to a far-future date on the way in:
 * the expiry index is partial on {@code expires_on IS NOT NULL}, and a
 * sentinel date would put every permanent document into the very index built
 * to exclude them.
 */
public record AttachDocumentRequest(
        @NotBlank String documentType,
        @NotBlank String documentNo,
        String issuingAuthority,
        LocalDate issuedOn,
        LocalDate expiresOn) {
}
