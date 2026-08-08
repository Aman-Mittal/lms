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
 * The synchronous surface of the order module.
 *
 * <p>Events are the default way into this module and remain so. This package
 * is the deliberate exception, for one caller and one reason: planning turns an
 * order into consignments as an operator action, and the operator expects to
 * see the result of the click they just made. Eventual consistency there would
 * mean pressing "plan" and watching nothing happen.
 */
@org.springframework.modulith.NamedInterface("api")
package com.lms.order.api;
