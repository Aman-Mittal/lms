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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.lms.client.api.DriversApi;
import com.lms.client.api.PartnersApi;
import com.lms.client.api.TerminalsApi;
import com.lms.client.api.VehiclesApi;
import com.lms.client.model.ComplianceDocument;
import com.lms.client.model.Driver;
import com.lms.client.model.DriverSlice;
import com.lms.client.model.Partner;
import com.lms.client.model.PartnerSlice;
import com.lms.client.model.Point;
import com.lms.client.model.RegisterDriverRequest;
import com.lms.client.model.RegisterPartnerRequest;
import com.lms.client.model.RegisterPointRadiusTerminalRequest;
import com.lms.client.model.RegisterPolygonTerminalRequest;
import com.lms.client.model.RegisterVehicleRequest;
import com.lms.client.model.Terminal;
import com.lms.client.model.TerminalSlice;
import com.lms.client.model.Vehicle;
import com.lms.client.model.VehicleSlice;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;

import static com.lms.e2e.DirectorySupport.refusedWith;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The master-data directory over HTTP: partners, vehicles, drivers and
 * terminals read back, filtered and paged by the keyset the API publishes,
 * and the refusals around them (unknown id, another tenant's id, a caller who
 * may not read, a terminal the geofence rules reject).
 *
 * <p>The listings are tenant-wide and the demo tenant is shared, so every
 * record here carries a code prefix of its own and every page is taken from a
 * cursor just below that prefix: nothing depends on how many other records
 * exist.
 */
public class DirectoryMasterDataSteps {

    @Value("${local.server.port}")
    private int port;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private HttpWorld world;
    private DirectorySupport support;
    private DirectorySupport.Cast cast;

    // partners
    private String partnerPrefix;
    private String partnerCursor;
    private List<UUID> partners;
    private List<String> partnerCodes;

    // vehicles
    private String vehicleCursor;
    private UUID lorryA;
    private UUID lorryB;
    private String regA;
    private String regB;

    // drivers
    private String driverCursor;
    private UUID driverA;
    private UUID driverB;
    private UUID employer;
    private String licenceA;
    private String licenceB;

    // terminals
    private UUID pointTerminal;
    private UUID polygonTerminal;
    private String polygonCode;
    private String pointCode;
    private BigDecimal lon0;

    @Given("the master-data directory is open to the demo administrator, a second tenant, a read-only user and a user with no permissions")
    public void open() {
        world = new HttpWorld(port);
        world.signInAsDemoAdministrator();
        support = new DirectorySupport(jdbc, transactionManager, passwordEncoder, port);
        cast = support.cast(world);
    }

    // ----------------------------------------------------------- partners

    @When("the directory registers a customer and two vendors, the last of them active, under a code prefix of its own")
    public void registerPartners() {
        String prefix = world.code("MDP");
        partnerPrefix = prefix;
        partnerCursor = prefix + "-0";
        UUID customer = world.partner(prefix + "-1", RegisterPartnerRequest.PartnerTypeEnum.CUSTOMER);
        UUID draftVendor = world.partner(prefix + "-2", RegisterPartnerRequest.PartnerTypeEnum.VENDOR);
        UUID activeVendor = world.activeVendor(prefix + "-3");
        partners = List.of(customer, draftVendor, activeVendor);
        partnerCodes = List.of(prefix + "-1-" + world.salt, prefix + "-2-" + world.salt,
                prefix + "-3-" + world.salt);
    }

    @Then("each directory partner reads back with its code, legal name, type, status and tax id")
    public void partnersReadBack() {
        PartnersApi api = world.api(PartnersApi.class);
        Partner customer = api.getPartner(partners.get(0));
        assertThat(customer.getId()).isEqualTo(partners.get(0));
        assertThat(customer.getCode()).isEqualTo(partnerCodes.get(0));
        assertThat(customer.getLegalName()).isEqualTo(partnerPrefix + "-1 " + world.salt + " Pvt Ltd");
        assertThat(customer.getPartnerType()).isEqualTo(Partner.PartnerTypeEnum.CUSTOMER);
        assertThat(customer.getStatus()).isEqualTo(Partner.StatusEnum.DRAFT);
        assertThat(customer.getTaxId()).isEqualTo("TAX-" + partnerPrefix + "-1-" + world.salt);
        assertThat(customer.getExpiredDocumentCount()).isZero();
        assertThat(customer.getCreatedAt()).isNotNull();

        assertThat(api.getPartner(partners.get(1)).getStatus()).isEqualTo(Partner.StatusEnum.DRAFT);
        Partner active = api.getPartner(partners.get(2));
        assertThat(active.getPartnerType()).isEqualTo(Partner.PartnerTypeEnum.VENDOR);
        assertThat(active.getStatus()).isEqualTo(Partner.StatusEnum.ACTIVE);
    }

    @Then("directory partners page two at a time from the cursor in code order")
    public void partnersPage() {
        PartnersApi api = world.api(PartnersApi.class);
        PartnerSlice first = api.listPartners(null, null, partnerCursor, 2);
        assertThat(first.getItems()).extracting(Partner::getCode)
                .containsExactly(partnerCodes.get(0), partnerCodes.get(1));
        assertThat(first.getItems()).extracting(Partner::getId)
                .containsExactly(partners.get(0), partners.get(1));
        assertThat(first.getHasMore()).as("the third partner is still to come").isTrue();
        assertThat(first.getNextCursor()).isEqualTo(partnerCodes.get(1));

        PartnerSlice second = api.listPartners(null, null, first.getNextCursor(), 2);
        assertThat(second.getItems().get(0).getCode()).isEqualTo(partnerCodes.get(2));

        PartnerSlice single = api.listPartners(null, null, partnerCursor, 1);
        assertThat(single.getItems()).hasSize(1);
        assertThat(single.getHasMore()).isTrue();
        assertThat(single.getNextCursor()).isEqualTo(partnerCodes.get(0));
    }

    @Then("directory partner filters by type and status pick out exactly the right one of the three")
    public void partnerFilters() {
        PartnersApi api = world.api(PartnersApi.class);
        assertThat(api.listPartners("VENDOR", "ACTIVE", partnerCursor, 1).getItems())
                .extracting(Partner::getId).containsExactly(partners.get(2));
        assertThat(api.listPartners("VENDOR", "DRAFT", partnerCursor, 1).getItems())
                .extracting(Partner::getId).containsExactly(partners.get(1));
        assertThat(api.listPartners("CUSTOMER", null, partnerCursor, 1).getItems())
                .extracting(Partner::getId).containsExactly(partners.get(0));
        assertThat(api.listPartners(null, "BLACKLISTED", partnerCursor, 5).getItems())
                .extracting(Partner::getId).doesNotContainAnyElementsOf(partners);
    }

    @Then("an unknown directory partner is a 404 problem and so is another tenant's partner")
    public void partnerNotFound() {
        PartnersApi api = world.api(PartnersApi.class);
        UUID theirs = world.client(cast.otherAdmin().getAccessToken(), PartnersApi.class)
                .registerPartner(new RegisterPartnerRequest().orgUnitId(cast.otherAdmin().getOrgUnitId())
                        .code("THEIRS-" + world.salt).legalName("Their partner")
                        .partnerType(RegisterPartnerRequest.PartnerTypeEnum.CUSTOMER), null).getId();

        refusedWith(404, "not-found", () -> api.getPartner(UUID.randomUUID()));
        refusedWith(404, "not-found", () -> api.getPartner(theirs));
        refusedWith(404, "not-found", () -> world.client(cast.otherAdmin().getAccessToken(), PartnersApi.class)
                .getPartner(partners.get(0)));
    }

    @Then("another tenant's directory does not list these partners but lists its own")
    public void partnersAreTenantScoped() {
        PartnersApi theirs = world.client(cast.otherAdmin().getAccessToken(), PartnersApi.class);
        List<String> theirCodes = theirs.listPartners(null, null, null, 200).getItems().stream()
                .map(Partner::getCode).toList();
        assertThat(theirCodes).contains("THEIRS-" + world.salt).doesNotContainAnyElementsOf(partnerCodes);
        assertThat(world.api(PartnersApi.class).listPartners(null, null, "THEIRS-", 5).getItems())
                .extracting(Partner::getCode).doesNotContain("THEIRS-" + world.salt);
    }

    @Then("directory partners are refused as 403 to a user with no permissions and readable by a read-only user")
    public void partnerPermissions() {
        PartnersApi nobody = world.client(cast.nobody().getAccessToken(), PartnersApi.class);
        refusedWith(403, "access-denied", () -> nobody.listPartners(null, null, null, null));
        refusedWith(403, "access-denied", () -> nobody.getPartner(partners.get(0)));

        PartnersApi reader = world.client(cast.reader().getAccessToken(), PartnersApi.class);
        assertThat(reader.getPartner(partners.get(0)).getCode()).isEqualTo(partnerCodes.get(0));
        assertThat(reader.listPartners(null, null, partnerCursor, 1).getItems())
                .extracting(Partner::getId).containsExactly(partners.get(0));
    }

    // ----------------------------------------------------------- vehicles

    @When("the directory registers two lorries of 8000 and 12000 kg payload under a registration prefix of its own")
    public void registerLorries() {
        vehicleCursor = "MH-" + world.salt;
        lorryA = world.lorry(world.salt + "A", 8000);
        lorryB = world.lorry(world.salt + "B", 12000);
        regA = "MH-" + world.salt + "A-" + world.salt;
        regB = "MH-" + world.salt + "B-" + world.salt;
    }

    @Then("directory vehicle {word} reads back with its registration, capacity, configuration and document summary")
    public void vehicleReadsBack(String which) {
        boolean first = which.equals("A");
        Vehicle vehicle = world.api(VehiclesApi.class).getVehicle(first ? lorryA : lorryB);
        assertThat(vehicle.getId()).isEqualTo(first ? lorryA : lorryB);
        assertThat(vehicle.getRegistrationNo()).isEqualTo(first ? regA : regB);
        assertThat(vehicle.getCategory()).isEqualTo("TRUCK");
        assertThat(vehicle.getVehicleType()).isEqualTo(HttpWorld.VEHICLE_TYPE);
        assertThat(vehicle.getAxleConfig()).isEqualTo("2-AXLE");
        assertThat(vehicle.getStatus()).isNotNull();
        assertThat(vehicle.getTareWeightKg()).isEqualByComparingTo(HttpWorld.TARE_KG);
        BigDecimal payload = new BigDecimal(first ? 8000 : 12000);
        assertThat(vehicle.getPayloadCapacityKg()).as("gross less tare").isEqualByComparingTo(payload);
        assertThat(vehicle.getGrossWeightKg()).isEqualByComparingTo(HttpWorld.TARE_KG.add(payload));
        assertThat(vehicle.getMaxVolumeM3()).isEqualByComparingTo("60");
        assertThat(vehicle.getHazmatCertified()).isFalse();
        assertThat(vehicle.getReeferCapable()).isFalse();
        assertThat(vehicle.getOwnerPartnerId()).isNull();
        assertThat(vehicle.getEarliestDocumentExpiry()).as("the insurance filed with the lorry")
                .isAfter(LocalDate.now(ZoneOffset.UTC).plusMonths(11))
                .isBefore(LocalDate.now(ZoneOffset.UTC).plusMonths(13));
        assertThat(vehicle.getExpiredDocumentCount()).isZero();
        assertThat(vehicle.getCreatedAt()).isNotNull();
    }

    @Then("the directory lists the two lorries in registration order, pages them and filters them by status")
    public void vehicleListing() {
        VehiclesApi api = world.api(VehiclesApi.class);
        VehicleSlice both = api.listVehicles(null, vehicleCursor, 2);
        assertThat(both.getItems()).extracting(Vehicle::getRegistrationNo).containsExactly(regA, regB);

        VehicleSlice first = api.listVehicles(null, vehicleCursor, 1);
        assertThat(first.getItems()).extracting(Vehicle::getId).containsExactly(lorryA);
        assertThat(first.getHasMore()).isTrue();
        assertThat(first.getNextCursor()).isEqualTo(regA);
        assertThat(api.listVehicles(null, first.getNextCursor(), 1).getItems())
                .extracting(Vehicle::getId).containsExactly(lorryB);

        Vehicle.StatusEnum status = api.getVehicle(lorryA).getStatus();
        assertThat(api.listVehicles(status.getValue(), vehicleCursor, 2).getItems())
                .extracting(Vehicle::getId).containsExactly(lorryA, lorryB);
        assertThat(api.listVehicles("RETIRED", vehicleCursor, 2).getItems())
                .extracting(Vehicle::getId).doesNotContain(lorryA, lorryB);
    }

    @Then("the directory shows the insurance filed with lorry A, and no documents yet for a fresh owner")
    public void vehicleDocuments() {
        VehiclesApi api = world.api(VehiclesApi.class);
        List<ComplianceDocument> documents = api.listVehicleDocuments(lorryA);
        assertThat(documents).hasSize(1);
        ComplianceDocument insurance = documents.get(0);
        assertThat(insurance.getOwnerType()).isEqualTo(ComplianceDocument.OwnerTypeEnum.VEHICLE);
        assertThat(insurance.getOwnerId()).isEqualTo(lorryA);
        assertThat(insurance.getOwnerLabel()).isEqualTo(regA);
        assertThat(insurance.getDocumentType()).isEqualTo("INSURANCE");
        assertThat(insurance.getDocumentNo()).isEqualTo("INS-" + world.salt + "A-" + world.salt);
        assertThat(insurance.getIssuingAuthority()).isEqualTo("IRDAI");
        assertThat(insurance.getIssuedOn()).isEqualTo(LocalDate.now(ZoneOffset.UTC).minusYears(1));
        assertThat(insurance.getExpiresOn()).isEqualTo(LocalDate.now(ZoneOffset.UTC).plusYears(1));
        assertThat(insurance.getExpired()).isFalse();
        assertThat(insurance.getDaysToExpiry()).isBetween(363, 367);

        assertThat(api.listVehicleDocuments(UUID.randomUUID())).as("no such owner, nothing filed").isEmpty();
    }

    @Then("an unknown vehicle, another tenant's vehicle and a caller who may not read are each refused")
    public void vehicleRefusals() {
        VehiclesApi api = world.api(VehiclesApi.class);
        UUID theirs = world.client(cast.otherAdmin().getAccessToken(), VehiclesApi.class)
                .registerVehicle(new RegisterVehicleRequest()
                        .orgUnitId(cast.otherAdmin().getOrgUnitId())
                        .registrationNo("THEIRS-" + world.salt).category("TRUCK")
                        .vehicleType("RIGID").axleConfig("2-AXLE")
                        .grossWeightKg(new BigDecimal("16000")).tareWeightKg(new BigDecimal("6000"))
                        .maxVolumeM3(new BigDecimal("40")).hazmatCertified(false).reeferCapable(false),
                        null).getId();

        refusedWith(404, "not-found", () -> api.getVehicle(UUID.randomUUID()));
        refusedWith(404, "not-found", () -> api.getVehicle(theirs));
        assertThat(api.listVehicles(null, "THEIRS-", 5).getItems())
                .extracting(Vehicle::getId).doesNotContain(theirs);
        assertThat(api.listVehicleDocuments(theirs)).as("another tenant's owner shows nothing").isEmpty();

        VehiclesApi nobody = world.client(cast.nobody().getAccessToken(), VehiclesApi.class);
        refusedWith(403, "access-denied", () -> nobody.listVehicles(null, null, null));
        refusedWith(403, "access-denied", () -> nobody.getVehicle(lorryA));
        refusedWith(403, "access-denied", () -> nobody.listVehicleDocuments(lorryA));

        VehiclesApi reader = world.client(cast.reader().getAccessToken(), VehiclesApi.class);
        assertThat(reader.getVehicle(lorryA).getRegistrationNo()).isEqualTo(regA);
        assertThat(reader.listVehicleDocuments(lorryA)).hasSize(1);
    }

    // ------------------------------------------------------------ drivers

    @When("the directory registers two drivers, the second employed by an active vendor, under a licence prefix of its own")
    public void registerDrivers() {
        driverCursor = "DL-" + world.salt;
        driverA = world.driver(world.salt + "A");
        licenceA = "DL-" + world.salt + "A-" + world.salt;
        licenceB = "DL-" + world.salt + "B-" + world.salt;
        employer = world.activeVendor("MDE");
        driverB = world.api(DriversApi.class).registerDriver(new RegisterDriverRequest()
                .orgUnitId(world.orgUnitId()).employerPartnerId(employer)
                .fullName("Hazmat Driver " + world.salt).phone("+910000000002")
                .licenceNo(licenceB).licenceClass("HAZMAT").licenceAuthority("RTO Pune")
                .licenceExpiresOn(LocalDate.now(ZoneOffset.UTC).plusMonths(6))
                .hazmatEndorsed(true), null).getId();
    }

    @Then("each directory driver reads back with its licence, endorsement, status and employer")
    public void driversReadBack() {
        DriversApi api = world.api(DriversApi.class);
        Driver a = api.getDriver(driverA);
        assertThat(a.getId()).isEqualTo(driverA);
        assertThat(a.getFullName()).isEqualTo("Driver " + world.salt + "A");
        assertThat(a.getPhone()).isEqualTo("+910000000001");
        assertThat(a.getLicenceNo()).isEqualTo(licenceA);
        assertThat(a.getLicenceClass()).isEqualTo("HEAVY");
        assertThat(a.getLicenceExpiresOn()).isEqualTo(LocalDate.now(ZoneOffset.UTC).plusYears(1));
        assertThat(a.getLicenceExpired()).isFalse();
        assertThat(a.getHazmatEndorsed()).isFalse();
        assertThat(a.getStatus()).isNotNull();
        assertThat(a.getHosMinutesToday()).isNotNull();
        assertThat(a.getEmployerPartnerId()).isNull();
        assertThat(a.getCreatedAt()).isNotNull();

        Driver b = api.getDriver(driverB);
        assertThat(b.getFullName()).isEqualTo("Hazmat Driver " + world.salt);
        assertThat(b.getLicenceClass()).isEqualTo("HAZMAT");
        assertThat(b.getHazmatEndorsed()).isTrue();
        assertThat(b.getEmployerPartnerId()).isEqualTo(employer);
        assertThat(b.getEmployerPartnerName()).isEqualTo("MDE " + world.salt + " Pvt Ltd");
    }

    @Then("the directory lists the two drivers in licence order, pages them and filters them by status")
    public void driverListing() {
        DriversApi api = world.api(DriversApi.class);
        DriverSlice both = api.listDrivers(null, driverCursor, 2);
        assertThat(both.getItems()).extracting(Driver::getLicenceNo).containsExactly(licenceA, licenceB);

        DriverSlice first = api.listDrivers(null, driverCursor, 1);
        assertThat(first.getItems()).extracting(Driver::getId).containsExactly(driverA);
        assertThat(first.getHasMore()).isTrue();
        assertThat(first.getNextCursor()).isEqualTo(licenceA);
        assertThat(api.listDrivers(null, first.getNextCursor(), 1).getItems())
                .extracting(Driver::getId).containsExactly(driverB);

        Driver.StatusEnum status = api.getDriver(driverA).getStatus();
        assertThat(api.listDrivers(status.getValue(), driverCursor, 2).getItems())
                .extracting(Driver::getId).contains(driverA);
        assertThat(api.listDrivers("INACTIVE", driverCursor, 2).getItems())
                .extracting(Driver::getId).doesNotContain(driverA, driverB);
        assertThat(api.listDriverDocuments(driverA)).as("nothing filed yet").isEmpty();
    }

    @Then("an unknown driver, another tenant's driver and a caller who may not read are each refused")
    public void driverRefusals() {
        DriversApi api = world.api(DriversApi.class);
        UUID theirs = world.client(cast.otherAdmin().getAccessToken(), DriversApi.class)
                .registerDriver(new RegisterDriverRequest().orgUnitId(cast.otherAdmin().getOrgUnitId())
                        .fullName("Their driver").phone("+910000000003").licenceNo("THEIRS-" + world.salt)
                        .licenceClass("HEAVY").licenceExpiresOn(LocalDate.now(ZoneOffset.UTC).plusYears(1))
                        .hazmatEndorsed(false), null).getId();

        refusedWith(404, "not-found", () -> api.getDriver(UUID.randomUUID()));
        refusedWith(404, "not-found", () -> api.getDriver(theirs));
        assertThat(api.listDrivers(null, "THEIRS-", 5).getItems())
                .extracting(Driver::getId).doesNotContain(theirs);

        DriversApi nobody = world.client(cast.nobody().getAccessToken(), DriversApi.class);
        refusedWith(403, "access-denied", () -> nobody.listDrivers(null, null, null));
        refusedWith(403, "access-denied", () -> nobody.getDriver(driverA));
        // A driver's documents are read under the vehicle permission.
        refusedWith(403, "access-denied", () -> nobody.listDriverDocuments(driverA));

        DriversApi reader = world.client(cast.reader().getAccessToken(), DriversApi.class);
        assertThat(reader.getDriver(driverA).getLicenceNo()).isEqualTo(licenceA);
        refusedWith(409, "driver-licence-taken", () -> api.registerDriver(new RegisterDriverRequest()
                .orgUnitId(world.orgUnitId()).fullName("Same licence").phone("+910000000004")
                .licenceNo(licenceA).licenceClass("HEAVY")
                .licenceExpiresOn(LocalDate.now(ZoneOffset.UTC).plusYears(1)).hazmatEndorsed(false), null));
    }

    // ---------------------------------------------------------- terminals

    @When("the directory registers a point-radius depot and a polygon hub in its own corner of the map")
    public void registerTerminals() {
        pointCode = world.code("MDT-PT");
        polygonCode = world.code("MDT-POLY");
        lon0 = world.shiftedLon(10.0);
        pointTerminal = world.terminal(RegisterPointRadiusTerminalRequest.CategoryEnum.CUSTOMER_SITE,
                "MDT-PT", 40.10, 10.0);
        polygonTerminal = world.api(TerminalsApi.class).registerPolygonTerminal(polygon(polygonCode,
                RegisterPolygonTerminalRequest.CategoryEnum.HUB, 41.10, lon0), null).getId();
    }

    private RegisterPolygonTerminalRequest polygon(String code,
                                                   RegisterPolygonTerminalRequest.CategoryEnum category,
                                                   double lat0, BigDecimal lonWest) {
        BigDecimal lat = BigDecimal.valueOf(lat0);
        BigDecimal step = new BigDecimal("0.01");
        return new RegisterPolygonTerminalRequest().orgUnitId(world.orgUnitId())
                .code(code).name("Hub " + code).category(category)
                .ring(List.of(new Point().lat(lat).lon(lonWest),
                        new Point().lat(lat).lon(lonWest.add(step)),
                        new Point().lat(lat.add(step)).lon(lonWest.add(step)),
                        new Point().lat(lat.add(step)).lon(lonWest)))
                .dockCount(4).opensAt("06:00").closesAt("22:00").avgDwellMinutes(30);
    }

    @Then("the directory shows each terminal with its geofence, docks and hours")
    public void terminalsReadBack() {
        TerminalsApi api = world.api(TerminalsApi.class);
        Terminal hub = api.getTerminal(polygonTerminal);
        assertThat(hub.getId()).isEqualTo(polygonTerminal);
        assertThat(hub.getCode()).isEqualTo(polygonCode);
        assertThat(hub.getName()).isEqualTo("Hub " + polygonCode);
        assertThat(hub.getFunctionalCategory()).isEqualTo(Terminal.FunctionalCategoryEnum.HUB);
        assertThat(hub.getGeofenceType()).isEqualTo(Terminal.GeofenceTypeEnum.POLYGON);
        assertThat(hub.getPolygon()).as("GeoJSON ring").isNotBlank().contains("[");
        assertThat(hub.getCentreLat()).isNull();
        assertThat(hub.getRadiusM()).isNull();
        assertThat(hub.getDockCount()).isEqualTo(4);
        assertThat(hub.getOpensAt()).startsWith("06:00");
        assertThat(hub.getClosesAt()).startsWith("22:00");
        assertThat(hub.getAvgDwellMinutes()).isEqualTo(30);
        assertThat(hub.getCreatedAt()).isNotNull();

        Terminal depot = api.getTerminal(pointTerminal);
        assertThat(depot.getCode()).isEqualTo(pointCode);
        assertThat(depot.getFunctionalCategory()).isEqualTo(Terminal.FunctionalCategoryEnum.CUSTOMER_SITE);
        assertThat(depot.getGeofenceType()).isEqualTo(Terminal.GeofenceTypeEnum.POINT_RADIUS);
        assertThat(depot.getPolygon()).isNull();
        assertThat(depot.getCentreLat()).isEqualByComparingTo("40.10");
        assertThat(depot.getCentreLon()).isEqualByComparingTo(lon0);
        assertThat(depot.getRadiusM()).isEqualByComparingTo("500");
        assertThat(depot.getDockCount()).isEqualTo(2);
        assertThat(depot.getAvgDwellMinutes()).isEqualTo(45);
    }

    @Then("the directory lists terminals by category from a cursor and pages them")
    public void terminalListing() {
        TerminalsApi api = world.api(TerminalsApi.class);
        TerminalSlice hubs = api.listTerminals("HUB", "MDT-POLY-", 1);
        assertThat(hubs.getItems()).extracting(Terminal::getId).containsExactly(polygonTerminal);
        assertThat(api.listTerminals("CUSTOMER_SITE", "MDT-PT-", 1).getItems())
                .extracting(Terminal::getId).containsExactly(pointTerminal);
        assertThat(api.listTerminals("HUB", null, 200).getItems())
                .extracting(Terminal::getId).doesNotContain(pointTerminal);
        assertThat(api.listTerminals("PORT", "MDT-", 200).getItems())
                .extracting(Terminal::getId).doesNotContain(pointTerminal, polygonTerminal);

        TerminalSlice window = api.listTerminals(null, "MDT-P", 1);
        assertThat(window.getItems()).extracting(Terminal::getCode).containsExactly(polygonCode);
        assertThat(window.getHasMore()).isTrue();
        assertThat(window.getNextCursor()).isEqualTo(polygonCode);
        assertThat(api.listTerminals(null, window.getNextCursor(), 1).getItems())
                .extracting(Terminal::getCode).containsExactly(pointCode);
    }

    @Then("a second hub over the same ground, a repeated code and a malformed ring are each refused with their own code")
    public void terminalRefusals() {
        TerminalsApi api = world.api(TerminalsApi.class);
        refusedWith(409, "terminal-overlap", () -> api.registerPolygonTerminal(polygon(
                world.code("MDT-OVERLAP"), RegisterPolygonTerminalRequest.CategoryEnum.HUB, 41.105,
                lon0.add(new BigDecimal("0.005"))), null));
        refusedWith(409, "terminal-code-taken", () -> api.registerPolygonTerminal(polygon(
                polygonCode, RegisterPolygonTerminalRequest.CategoryEnum.HUB, 43.50, lon0), null));

        // The same ground is fine for a different category.
        UUID port = api.registerPolygonTerminal(polygon(world.code("MDT-PORT"),
                RegisterPolygonTerminalRequest.CategoryEnum.PORT, 41.10, lon0), null).getId();
        assertThat(api.getTerminal(port).getFunctionalCategory())
                .isEqualTo(Terminal.FunctionalCategoryEnum.PORT);

        BigDecimal lat = new BigDecimal("42.50");
        RegisterPolygonTerminalRequest bowtie = polygon(world.code("MDT-BOWTIE"),
                RegisterPolygonTerminalRequest.CategoryEnum.HUB, 42.50, lon0)
                .ring(List.of(new Point().lat(lat).lon(lon0),
                        new Point().lat(lat.add(new BigDecimal("0.01"))).lon(lon0.add(new BigDecimal("0.01"))),
                        new Point().lat(lat).lon(lon0.add(new BigDecimal("0.01"))),
                        new Point().lat(lat.add(new BigDecimal("0.01"))).lon(lon0)));
        refusedWith(400, "invalid-argument", () -> api.registerPolygonTerminal(bowtie, null));

        RegisterPolygonTerminalRequest twoPoints = polygon(world.code("MDT-LINE"),
                RegisterPolygonTerminalRequest.CategoryEnum.HUB, 42.60, lon0)
                .ring(List.of(new Point().lat(lat).lon(lon0),
                        new Point().lat(lat).lon(lon0.add(new BigDecimal("0.01")))));
        refusedWith(400, "invalid-argument", () -> api.registerPolygonTerminal(twoPoints, null));

        RegisterPolygonTerminalRequest offTheMap = polygon(world.code("MDT-OFF"),
                RegisterPolygonTerminalRequest.CategoryEnum.HUB, 42.70, lon0)
                .ring(List.of(new Point().lat(new BigDecimal("95")).lon(lon0),
                        new Point().lat(new BigDecimal("95")).lon(lon0.add(new BigDecimal("0.01"))),
                        new Point().lat(new BigDecimal("94")).lon(lon0)));
        refusedWith(400, "invalid-argument", () -> api.registerPolygonTerminal(offTheMap, null));

        JsonNode blank = refusedWith(400, "validation-failed", () -> api.registerPolygonTerminal(
                polygon(" ", RegisterPolygonTerminalRequest.CategoryEnum.HUB, 42.80, lon0), null));
        assertThat(blank.get("errors").has("code")).as("the offending field is named").isTrue();
    }

    @Then("another tenant may register the very same hub, and neither tenant can see the other's terminals")
    public void terminalsAreTenantScoped() {
        TerminalsApi theirs = world.client(cast.otherAdmin().getAccessToken(), TerminalsApi.class);
        UUID theirHub = theirs.registerPolygonTerminal(new RegisterPolygonTerminalRequest()
                .orgUnitId(cast.otherAdmin().getOrgUnitId()).code(polygonCode).name("Same hub, other tenant")
                .category(RegisterPolygonTerminalRequest.CategoryEnum.HUB)
                .ring(polygon(polygonCode, RegisterPolygonTerminalRequest.CategoryEnum.HUB, 41.10, lon0)
                        .getRing()), null).getId();
        assertThat(theirHub).isNotEqualTo(polygonTerminal);

        TerminalsApi ours = world.api(TerminalsApi.class);
        refusedWith(404, "not-found", () -> ours.getTerminal(theirHub));
        refusedWith(404, "not-found", () -> theirs.getTerminal(polygonTerminal));
        refusedWith(404, "not-found", () -> ours.getTerminal(UUID.randomUUID()));
        assertThat(theirs.listTerminals(null, null, 200).getItems())
                .extracting(Terminal::getId).contains(theirHub).doesNotContain(polygonTerminal, pointTerminal);
        assertThat(ours.getTerminal(polygonTerminal).getName()).isEqualTo("Hub " + polygonCode);
    }

    @Then("terminals are readable by a read-only user, who may not register one, and invisible to a user with no permissions")
    public void terminalPermissions() {
        TerminalsApi reader = world.client(cast.reader().getAccessToken(), TerminalsApi.class);
        assertThat(reader.getTerminal(polygonTerminal).getCode()).isEqualTo(polygonCode);
        assertThat(reader.listTerminals("HUB", "MDT-POLY-", 1).getItems())
                .extracting(Terminal::getId).containsExactly(polygonTerminal);
        refusedWith(403, "access-denied", () -> reader.registerPolygonTerminal(polygon(
                world.code("MDT-READER"), RegisterPolygonTerminalRequest.CategoryEnum.HUB, 43.10, lon0), null));

        TerminalsApi nobody = world.client(cast.nobody().getAccessToken(), TerminalsApi.class);
        refusedWith(403, "access-denied", () -> nobody.listTerminals(null, null, null));
        refusedWith(403, "access-denied", () -> nobody.getTerminal(polygonTerminal));
    }
}
