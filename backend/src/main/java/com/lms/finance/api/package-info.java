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
 * What finance asks of the module that bills customers, without depending on
 * it: an approved manual receivable becomes a charge on the customer's next
 * invoice, and is taken back while it has not been invoiced. Client billing
 * implements it, so finance never imports {@code clientbilling}.
 */
@org.springframework.modulith.NamedInterface("api")
package com.lms.finance.api;
