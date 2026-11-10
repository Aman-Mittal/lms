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
package com.lms.webhooks.command;

/**
 * What a subscription can ask to be told about.
 *
 * <p>{@link #PING} is not subscribable: it is what the test-ping action sends,
 * to every subscription that asks for one, whatever it subscribed to.
 */
public enum WebhookEventType {
    ORDER_VALIDATED,
    ORDER_RAISED,
    ORDER_AMENDED,
    ORDER_CANCELLED,
    ORDER_FULFILLED,
    ORDER_CLOSED,
    CONSIGNMENTS_GENERATED,
    CONSIGNMENT_SPLIT,
    TRIP_DISPATCHED,
    TRIP_COMPLETED,
    TRIP_CANCELLED,
    DELIVERY_PROOF_RECORDED,
    CLAIM_RAISED,
    CLAIM_STATUS_CHANGED,
    CONSIGNMENT_IN_TRANSIT,
    CONSIGNMENT_DELIVERED,
    CONSIGNMENT_CANCELLED,
    PING;

    public boolean subscribable() {
        return this != PING;
    }
}
