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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.lms.client.api.AuthenticationApi;
import com.lms.client.api.CustomFieldsApi;
import com.lms.client.model.CustomFieldDefinition;
import com.lms.client.model.CustomFieldEntityType;
import com.lms.client.model.CustomFieldType;
import com.lms.client.model.CustomFieldValues;
import com.lms.client.model.DefineCustomFieldRequest;
import com.lms.client.model.LoginRequest;
import com.lms.client.model.RegisterPartnerRequest;
import com.lms.client.model.ReviseCustomFieldRequest;
import feign.FeignException;
import io.cucumber.java.After;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Custom fields over HTTP.
 *
 * <p>The demo tenant is shared by every end-to-end scenario, and a required
 * field outlives the scenario that defined it, so each scenario here works on
 * its own kind of record (partners, vehicles, drivers) and every definition is
 * retired afterwards. A field defined here would otherwise make the next
 * scenario's unrelated write fail for want of a value it never heard of.
 *
 * <p>Keys carry the scenario's salt so a rerun against the same database finds
 * nothing already defined.
 */
public class CustomFieldSteps {

    @Value("${local.server.port}")
    private int port;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JwtEncoder jwtEncoder;

    private HttpWorld world;
    private ContractSupport support;
    private ContractSupport.Caller reader;
    private CustomFieldsApi readerApi;
    private CustomFieldsApi secondApi;
    private UUID customer;
    private UUID lorry;
    private UUID driver;
    private final List<UUID> defined = new ArrayList<>();
    private FeignException refusal;

    @After("@customfields")
    public void retireWhatWasDefined() {
        if (world == null) {
            return;
        }
        CustomFieldsApi api = world.api(CustomFieldsApi.class);
        for (CustomFieldDefinition d : api.listCustomFieldDefinitions((CustomFieldEntityType) null)
                .getItems()) {
            if (defined.contains(d.getId()) && Boolean.TRUE.equals(d.getActive())) {
                api.reviseCustomField(d.getId(), new ReviseCustomFieldRequest().label(d.getLabel())
                        .required(false).options(d.getOptions()).active(false));
            }
        }
        if (support != null && reader != null) {
            support.remove(reader);
        }
    }

    private String key(String name) {
        return name + "_" + world.salt.toLowerCase();
    }

    private UUID define(CustomFieldEntityType on, String name, CustomFieldType type, boolean required,
                        List<String> options) {
        UUID id = world.api(CustomFieldsApi.class).defineCustomField(new DefineCustomFieldRequest()
                .entityType(on).key(key(name)).label(name + " label").fieldType(type)
                .required(required).options(options), null).getId();
        defined.add(id);
        return id;
    }

    private void attempt(Runnable write) {
        refusal = null;
        try {
            write.run();
        } catch (FeignException e) {
            refusal = e;
        }
    }

    private CustomFieldValues valuesOf(CustomFieldEntityType on, UUID id) {
        return world.api(CustomFieldsApi.class).getCustomFieldValues(on, id);
    }

    private void record(CustomFieldEntityType on, UUID id, Map<String, Object> values) {
        world.api(CustomFieldsApi.class)
                .setCustomFieldValues(on, id, new CustomFieldValues().values(values));
    }

    // ------------------------------------------------------------ session

    @Given("the demo administrator is signed in at the custom fields desk")
    public void signIn() {
        world = new HttpWorld(port);
        world.signInAsDemoAdministrator();
    }

    @Given("a customer is registered for custom values")
    public void aCustomer() {
        customer = world.partner("CF", RegisterPartnerRequest.PartnerTypeEnum.CUSTOMER);
    }

    @Given("a lorry is registered for custom values")
    public void aLorry() {
        lorry = world.lorry("CF", 20000);
    }

    @Given("a driver is registered for custom values")
    public void aDriver() {
        driver = world.driver("CF");
    }

    // -------------------------------------------------------- definitions

    @Given("the shipper defines a required text field {string} and a choice field {string} of {string} or {string} for partners")
    public void partnerFields(String text, String choice, String a, String b) {
        define(CustomFieldEntityType.PARTNER, text, CustomFieldType.TEXT, true, List.of());
        define(CustomFieldEntityType.PARTNER, choice, CustomFieldType.SELECT, false, List.of(a, b));
    }

    @Given("the shipper defines a required text field {string} for partners")
    public void requiredPartnerField(String name) {
        define(CustomFieldEntityType.PARTNER, name, CustomFieldType.TEXT, true, List.of());
    }

    @Given("the shipper defines a number field {string}, a date field {string} and a flag field {string} for vehicles")
    public void vehicleFields(String number, String date, String flag) {
        define(CustomFieldEntityType.VEHICLE, number, CustomFieldType.NUMBER, false, List.of());
        define(CustomFieldEntityType.VEHICLE, date, CustomFieldType.DATE, false, List.of());
        define(CustomFieldEntityType.VEHICLE, flag, CustomFieldType.BOOLEAN, false, List.of());
    }

    @Given("the shipper defines a required text field {string} for drivers")
    public void driverField(String name) {
        define(CustomFieldEntityType.DRIVER, name, CustomFieldType.TEXT, true, List.of());
    }

    // ------------------------------------------------------------- partners

    @When("the shipper records only the tier on the customer")
    public void onlyTier() {
        attempt(() -> record(CustomFieldEntityType.PARTNER, customer,
                Map.of(key("tier"), "GOLD")));
    }

    @Then("the write is refused as a conflict naming {string} as required")
    public void refusedAsRequired(String name) {
        assertThat(refusal).as("the write was refused").isNotNull();
        assertThat(refusal.status()).isEqualTo(409);
        assertThat(refusal.contentUTF8()).contains("custom-field-invalid", key(name), "required");
    }

    @When("the shipper records the po {string} and the tier {string} on the customer")
    public void poAndTier(String po, String tier) {
        record(CustomFieldEntityType.PARTNER, customer, Map.of(key("po"), po, key("tier"), tier));
    }

    @Then("the customer's custom values are po {string} and tier {string}")
    public void customerValues(String po, String tier) {
        assertThat(valuesOf(CustomFieldEntityType.PARTNER, customer).getValues())
                .containsEntry(key("po"), po).containsEntry(key("tier"), tier).hasSize(2);
    }

    @Then("the partner definitions are listed with {string} before {string}")
    public void listedInOrder(String first, String second) {
        List<String> keys = world.api(CustomFieldsApi.class)
                .listCustomFieldDefinitions(CustomFieldEntityType.PARTNER).getItems().stream()
                .map(CustomFieldDefinition::getKey).toList();
        assertThat(keys).contains(key(first), key(second));
        assertThat(keys.indexOf(key(first))).isLessThan(keys.indexOf(key(second)));
    }

    // --------------------------------------------------------------- lorry

    @When("the shipper records axles {string}, fitness {string} and tracker {string} on the lorry")
    public void badLorry(String axles, String fitness, String tracker) {
        attempt(() -> record(CustomFieldEntityType.VEHICLE, lorry,
                Map.of(key("axles"), axles, key("fitness"), fitness, key("tracker"), tracker)));
    }

    @Then("the write is refused as a conflict naming {string}, {string} and {string}")
    public void refusedNaming(String a, String b, String c) {
        assertThat(refusal).as("the write was refused").isNotNull();
        assertThat(refusal.status()).isEqualTo(409);
        assertThat(refusal.contentUTF8()).contains(key(a), key(b), key(c));
    }

    @When("the shipper records axles {int}, fitness {string} and tracker true on the lorry")
    public void goodLorry(int axles, String fitness) {
        record(CustomFieldEntityType.VEHICLE, lorry,
                Map.of(key("axles"), axles, key("fitness"), fitness, key("tracker"), true));
    }

    @Then("the lorry's custom values are axles {int}, fitness {string} and tracker true")
    public void lorryValues(int axles, String fitness) {
        assertThat(valuesOf(CustomFieldEntityType.VEHICLE, lorry).getValues())
                .containsEntry(key("axles"), axles).containsEntry(key("fitness"), fitness)
                .containsEntry(key("tracker"), true);
    }

    // -------------------------------------------------------------- driver

    @Given("the shipper records the union {string} on the driver")
    public void firstUnion(String value) {
        record(CustomFieldEntityType.DRIVER, driver, Map.of(key("union"), value));
    }

    @When("the shipper retires the {string} field")
    public void retire(String name) {
        CustomFieldsApi api = world.api(CustomFieldsApi.class);
        CustomFieldDefinition d = api.listCustomFieldDefinitions(CustomFieldEntityType.DRIVER).getItems()
                .stream().filter(x -> key(name).equals(x.getKey())).findFirst().orElseThrow();
        api.reviseCustomField(d.getId(), new ReviseCustomFieldRequest().label(d.getLabel())
                .required(true).options(d.getOptions()).active(false));
    }

    @Then("the driver's custom values still hold the union {string}")
    public void unionKept(String value) {
        assertThat(valuesOf(CustomFieldEntityType.DRIVER, driver).getValues())
                .containsEntry(key("union"), value);
    }

    @Then("recording the union {string} on the driver is refused as an unknown field")
    public void unionRefused(String value) {
        assertThatThrownBy(() -> record(CustomFieldEntityType.DRIVER, driver,
                Map.of(key("union"), value)))
                .isInstanceOfSatisfying(FeignException.class, e -> {
                    assertThat(e.status()).isEqualTo(409);
                    assertThat(e.contentUTF8()).contains(key("union"), "not a field defined");
                });
    }

    // --------------------------------------------------------- permissions

    @Given("a custom field caller holding only {string}")
    public void readOnlyCaller(String permission) {
        support = new ContractSupport(port, jdbc, passwordEncoder, transactionManager, jwtEncoder);
        reader = support.provisionUser(HttpWorld.DEMO_TENANT, world.orgUnitId(), world.salt,
                List.of(permission));
        String token = support.login(HttpWorld.DEMO_TENANT, reader.email(), ContractSupport.PASSWORD);
        readerApi = world.client(token, CustomFieldsApi.class);
    }

    @Then("that caller can list the partner definitions")
    public void readerLists() {
        assertThat(readerApi.listCustomFieldDefinitions(CustomFieldEntityType.PARTNER).getItems())
                .isNotNull();
    }

    @Then("that caller may not define a field")
    public void readerCannotDefine() {
        assertThatThrownBy(() -> readerApi.defineCustomField(new DefineCustomFieldRequest()
                .entityType(CustomFieldEntityType.PARTNER).key(key("sneaky")).label("Sneaky")
                .fieldType(CustomFieldType.TEXT), null))
                .isInstanceOfSatisfying(FeignException.class,
                        e -> assertThat(e.status()).isEqualTo(403));
    }

    @Then("that caller may not record values on the customer")
    public void readerCannotRecord() {
        assertThatThrownBy(() -> readerApi.setCustomFieldValues(CustomFieldEntityType.PARTNER,
                customer, new CustomFieldValues().values(Map.of())))
                .isInstanceOfSatisfying(FeignException.class,
                        e -> assertThat(e.status()).isEqualTo(403));
    }

    // ------------------------------------------------------- second company

    @Given("a second company with its own administrator is signed in for custom fields")
    public void secondCompany() {
        support = new ContractSupport(port, jdbc, passwordEncoder, transactionManager, jwtEncoder);
        ContractSupport.OtherTenant other = support.provisionTenant(world.salt);
        String token = world.client(null, AuthenticationApi.class).login(new LoginRequest()
                .tenantCode(other.code()).email(other.email()).password(ContractSupport.PASSWORD))
                .getAccessToken();
        secondApi = world.client(token, CustomFieldsApi.class);
    }

    @Then("the second company sees no definition named {string} for partners")
    public void secondSeesNone(String name) {
        assertThat(secondApi.listCustomFieldDefinitions(CustomFieldEntityType.PARTNER).getItems())
                .extracting(CustomFieldDefinition::getKey).doesNotContain(key(name));
    }

    @Then("the second company is told the customer does not exist when it records values")
    public void secondNotFound() {
        assertThatThrownBy(() -> secondApi.setCustomFieldValues(CustomFieldEntityType.PARTNER,
                customer, new CustomFieldValues().values(Map.of())))
                .isInstanceOfSatisfying(FeignException.class,
                        e -> assertThat(e.status()).isEqualTo(404));
    }
}
