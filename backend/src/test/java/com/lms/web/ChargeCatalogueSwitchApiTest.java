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
package com.lms.web;

import java.util.UUID;

import com.lms.web.ApiTestKit.Outcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Switching a catalogue charge off and on over HTTP (issue 247): with a reason,
 * out of the default list, refused for new charges while off, and refused
 * outright while a charge raised from it waits for approval.
 */
class ChargeCatalogueSwitchApiTest extends MoneyApiSupport {

    private static void expect(String what, Outcome outcome, int status, String code) {
        assertThat(outcome.status()).as(what + ": " + outcome.body()).isEqualTo(status);
        if (code != null) {
            assertThat(outcome.code()).as(what).isEqualTo(code);
        }
    }

    private Outcome switchOver(UUID entry, String verb, String reason, String ifMatch) {
        return send("POST", "/api/v1/charge-catalogue/" + entry + "/" + verb,
                reason == null ? "{}" : json("reason", q(reason)), ifMatch, CATALOGUE);
    }

    private JsonNode listed(String query) {
        return kit.parse(get("/api/v1/charge-catalogue" + query, CATALOGUE)).get("items");
    }

    private boolean contains(JsonNode items, UUID id) {
        for (JsonNode item : items) {
            if (id.toString().equals(item.get("id").asString())) {
                return true;
            }
        }
        return false;
    }

    @Test
    @DisplayName("a catalogue charge is switched off with a reason, leaves the default list, and comes back")
    void switchOffAndOn() {
        UUID entry = entry("TOLL-" + UUID.randomUUID().toString().substring(0, 6), "FLAT", "250", "PAYABLE");
        UUID trip = raiseSingleDropTrip();
        String path = "/api/v1/charge-catalogue/" + entry;

        expect("no reason", switchOver(entry, "deactivate", null, null), 400, null);
        expect("a stale version", switchOver(entry, "deactivate", "Retired", "\"7\""), 409, "concurrent-modification");
        expect("switched off", switchOver(entry, "deactivate", "Retired by finance", etagOf(path, CATALOGUE)), 204, null);
        expect("off twice", switchOver(entry, "deactivate", "Again", null), 409, "charge-already-inactive");

        assertThat(contains(listed(""), entry)).as("the default list is the active entries").isFalse();
        assertThat(contains(listed("?status=INACTIVE"), entry)).isTrue();
        assertThat(contains(listed("?status=ALL"), entry)).isTrue();
        assertThat(kit.parse(get(path, CATALOGUE)).get("active").asBoolean()).isFalse();
        expect("a status nobody defined", get("/api/v1/charge-catalogue?status=GONE", CATALOGUE), 400, null);
        expect("not offered for a new charge", charge(entry, "TRIP", trip, "1", null, null), 409, "charge-inactive");
        assertThat(audited("CHARGE_CATALOGUE_DEACTIVATED", entry.toString())).isEqualTo(1);
        assertThat(jdbc.sql("SELECT after_state ->> 'reason' FROM audit_log WHERE action = 'CHARGE_CATALOGUE_DEACTIVATED'"
                + " AND resource_id = :id").param("id", entry).query(String.class).single())
                .isEqualTo("Retired by finance");

        expect("on again", switchOver(entry, "reactivate", "Needed again", null), 204, null);
        expect("on twice", switchOver(entry, "reactivate", "Again", null), 409, "charge-not-inactive");
        assertThat(contains(listed(""), entry)).isTrue();
        expect("offered again", charge(entry, "TRIP", trip, "1", null, null), 201, null);
        assertThat(audited("CHARGE_CATALOGUE_REACTIVATED", entry.toString())).isEqualTo(1);
    }

    @Test
    @DisplayName("an entry a charge waiting for approval was raised from is in use and stays on")
    void inUse() {
        UUID entry = entry("BIG-" + UUID.randomUUID().toString().substring(0, 6), "FLAT", "12000", "PAYABLE");
        UUID trip = raiseSingleDropTrip();
        expect("above the threshold waits", charge(entry, "TRIP", trip, "1", null, null), 202, null);
        expect("in use", switchOver(entry, "deactivate", "Retired", null), 409, "charge-in-use");
        assertThat(kit.parse(get("/api/v1/charge-catalogue/" + entry, CATALOGUE)).get("active").asBoolean()).isTrue();
    }
}
