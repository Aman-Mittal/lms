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
package com.lms.e2e;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.lms.client.api.SettingsApi;
import com.lms.client.api.UsersApi;
import com.lms.client.model.CreateSettingProfileRequest;
import com.lms.client.model.EffectiveSetting;
import com.lms.client.model.HistoryEntry;
import com.lms.client.model.Me;
import com.lms.client.model.MyPreferences;
import com.lms.client.model.RegisterPartnerRequest;
import com.lms.client.model.RegisterPointRadiusTerminalRequest;
import com.lms.client.model.SettingScopeType;
import com.lms.client.model.SettingVersionRequest;
import com.lms.client.model.UpdateMyPreferencesRequest;
import feign.FeignException;
import io.cucumber.java.After;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.beans.factory.annotation.Value;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A person's profile page and a customer's own settings, over HTTP, in the
 * shared demo tenant. Whatever this scenario changes -- the administrator's
 * own preferences, a customer profile -- is put back in an {@code @After}
 * hook, so no other scenario sees it.
 */
public class SettingsFlowSteps {

    private static final String TOLERANCE = "order.close.tolerancePercent";

    @Value("${local.server.port}")
    private int port;

    private HttpWorld world;
    private Me me;
    private MyPreferences saved;
    private UUID customer;
    private UUID profileId;
    private UUID checklistCustomer;

    @After("@settings-e2e")
    public void putBack() {
        if (world == null) {
            return;
        }
        if (profileId != null) {
            try {
                String etag = "\"" + api().getSettingProfile(profileId).getVersion() + "\"";
                api().deleteSettingProfile(etag, profileId);
            } catch (FeignException e) {
                // Already gone.
            }
        }
        // A null clears a preference; sent by hand so no serialiser drops it.
        long version = api().getMyPreferences().getVersion();
        send("PATCH", "/api/v1/settings/me", "\"" + version + "\"",
                "{\"values\":{\"ui.theme\":null,\"ui.defaultPageSize\":null}}");
    }

    @Given("the demo administrator is signed in to their profile page")
    public void signIn() {
        world = new HttpWorld(port);
        world.signInAsDemoAdministrator();
    }

    @When("the administrator asks who they are")
    public void whoAmI() {
        me = world.api(UsersApi.class).getMe();
    }

    @Then("they are told their own name, tenant, unit, roles and permissions")
    public void toldWhoTheyAre() {
        assertThat(me.getUserId()).isEqualTo(world.userId());
        assertThat(me.getOrgUnitId()).isEqualTo(world.orgUnitId());
        assertThat(me.getTenantCode()).isNotBlank();
        assertThat(me.getEmail()).contains("@");
        assertThat(me.getRoles()).isNotEmpty();
        assertThat(me.getPermissions()).contains("SETTINGS_MANAGE", "SETTINGS_READ");
    }

    @When("the administrator chooses the dark theme and 25 rows a page")
    public void choose() {
        MyPreferences before = api().getMyPreferences();
        saved = api().updateMyPreferences("\"" + before.getVersion() + "\"", new UpdateMyPreferencesRequest()
                .values(Map.of("ui.theme", "dark", "ui.defaultPageSize", 25)), null);
    }

    @Then("their preferences read back as theirs, and the other preferences as the platform's")
    public void readBack() {
        MyPreferences now = api().getMyPreferences();
        assertThat(now.getVersion()).isEqualTo(saved.getVersion());
        assertThat(now.getValues()).containsEntry("ui.theme", "dark");
        EffectiveSetting theme = entry(now.getEffective(), "ui.theme");
        assertThat(theme.getValue()).isEqualTo("dark");
        assertThat(theme.getSource().getScopeType()).isEqualTo(SettingScopeType.USER);
        assertThat(entry(now.getEffective(), "ui.timeZone").getSource().getKind().getValue())
                .isEqualTo("SYSTEM_DEFAULT");
    }

    @Then("saving again with the version they first read is refused with {string}")
    public void staleRefused(String code) {
        assertRefused(() -> api().updateMyPreferences("\"" + (saved.getVersion() - 1) + "\"",
                new UpdateMyPreferencesRequest().values(Map.of("ui.theme", "light")), null), 409, code);
    }

    @Then("setting a tenant rule as a personal preference is refused with {string}")
    public void notPersonal(String code) {
        assertRefused(() -> api().updateMyPreferences("\"" + saved.getVersion() + "\"",
                new UpdateMyPreferencesRequest().values(Map.of(TOLERANCE, 5)), null), 409, code);
    }

    @When("the administrator gives a new customer its own close tolerance of {int} percent")
    public void customerProfile(int percent) {
        customer = world.partner("SETCUST", RegisterPartnerRequest.PartnerTypeEnum.CUSTOMER);
        profileId = api().createSettingProfile(new CreateSettingProfileRequest().scopeType(SettingScopeType.CUSTOMER)
                .scopeId(customer).name("Terms " + world.salt).values(Map.of(TOLERANCE, percent)), null).getId();
    }

    @Then("the close tolerance in force for that customer is {int} percent, from its own profile")
    public void inForce(int percent) {
        EffectiveSetting tolerance = entry(api().getEffectiveSettings(world.orgUnitId(), customer, null, null, null, null, null, null, null).getItems(),
                TOLERANCE);
        assertThat(((Number) tolerance.getValue()).intValue()).isEqualTo(percent);
        assertThat(tolerance.getSource().getProfileId()).isEqualTo(profileId);
        assertThat(tolerance.getSource().getScopeType()).isEqualTo(SettingScopeType.CUSTOMER);
    }

    @When("the administrator gives a new customer a dispatch checklist that also requires a packing list")
    public void checklist() {
        checklistCustomer = world.partner("SETDSP", RegisterPartnerRequest.PartnerTypeEnum.CUSTOMER);
        api().createSettingProfile(new CreateSettingProfileRequest().scopeType(SettingScopeType.CUSTOMER)
                .scopeId(checklistCustomer).name("Checklist " + world.salt)
                .versions(List.of(new SettingVersionRequest().key("execution.dispatch.requiredDocuments")
                        .effectiveFrom(LocalDate.now(ZoneOffset.UTC))
                        .value(Map.of("CONSIGNMENT_NOTE", true, "EWAY_BILL", true, "PACKING_LIST", true)))),
                null);
    }

    @Then("a trip for that customer carrying only a consignment note and an e-way bill is refused, naming the packing list and the customer's profile")
    public void dispatchRefused() {
        UUID consignee = world.partner("SETCNS", RegisterPartnerRequest.PartnerTypeEnum.CONSIGNEE);
        UUID origin = world.terminal(RegisterPointRadiusTerminalRequest.CategoryEnum.CUSTOMER_SITE, "SET-ORG",
                66.10, 72.90);
        UUID destination = world.terminal(RegisterPointRadiusTerminalRequest.CategoryEnum.CUSTOMER_SITE,
                "SET-DST", 66.30, 73.10);
        UUID trip = world.assignedTrip("SET", checklistCustomer, consignee, origin, destination,
                world.activeVendor("SETV"), world.lorry("SET", 20000), world.driver("SET"), 5000);

        assertThatThrownBy(() -> world.dispatch(trip, origin, 5000, OffsetDateTime.now(ZoneOffset.UTC)))
                .isInstanceOfSatisfying(FeignException.class, e -> {
                    assertThat(e.status()).isEqualTo(409);
                    assertThat(e.contentUTF8()).contains("\"dispatch-blocked\"")
                            .contains("no packing list attached (required by customer profile")
                            .contains("Checklist " + world.salt)
                            .doesNotContain("no eway bill attached");
                });
    }

    @Then("the profile's history records who created it and with what")
    public void history() {
        List<HistoryEntry> items = api().getSettingProfileHistory(profileId, null, null).getItems();
        assertThat(items).hasSize(1);
        assertThat(items.get(0).getAction()).isEqualTo("SETTING_PROFILE_CREATED");
        assertThat(items.get(0).getActorId()).isEqualTo(world.userId());
        assertThat(items.get(0).getAfter()).containsKey(TOLERANCE);
    }

    private SettingsApi api() {
        return world.api(SettingsApi.class);
    }

    private static EffectiveSetting entry(List<EffectiveSetting> items, String key) {
        return items.stream().filter(i -> key.equals(i.getKey())).findFirst()
                .orElseThrow(() -> new AssertionError("No effective " + key));
    }

    private void send(String method, String path, String ifMatch, String body) {
        try {
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                            .header("Authorization", "Bearer " + world.accessToken())
                            .header("Content-Type", "application/json")
                            .header("If-Match", ifMatch)
                            .method(method, HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        } catch (IOException e) {
            throw new AssertionError("The request never reached the server", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted", e);
        }
    }

    private static void assertRefused(Runnable call, int status, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(FeignException.class, e -> {
            assertThat(e.status()).isEqualTo(status);
            assertThat(e.contentUTF8()).contains("\"" + code + "\"");
        });
    }
}
