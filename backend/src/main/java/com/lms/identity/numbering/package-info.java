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

/**
 * Document numbering, as other modules may use it.
 *
 * <p>A tenant configures number series in the administration desk: one per
 * {@link com.lms.identity.numbering.DocumentType} and scope (the whole tenant,
 * or one organisational unit and its subtree). A module that numbers a
 * document asks {@link com.lms.identity.numbering.DocumentNumbers} for the
 * next number at the document's unit; the most specific active series
 * answers, and an empty answer means the tenant has not configured one, so the
 * module keeps numbering the way it always has.
 *
 * <p>Two calls and nothing else, so that a consumer depends on the idea of a
 * number and not on how series are stored. Declare
 * {@code allowedDependencies = "identity::numbering"} to use them.
 */
@org.springframework.modulith.NamedInterface("numbering")
package com.lms.identity.numbering;
