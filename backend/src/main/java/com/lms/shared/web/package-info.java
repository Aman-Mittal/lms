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
 *//**
 * Shapes every context's HTTP layer needs.
 *
 * <p>Deliberately thin: an identifier envelope and nothing else. The temptation
 * with a package like this is to accumulate a base controller and a bag of
 * shared request records, at which point a change to one context's API
 * recompiles all eight. What lives here has to be genuinely common and
 * genuinely trivial.
 */
package com.lms.shared.web;
