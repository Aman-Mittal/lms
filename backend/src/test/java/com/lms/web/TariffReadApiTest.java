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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.lms.acceptance.TestFixtures;
import com.lms.client.ApiClient;
import com.lms.client.api.SettlementApi;
import com.lms.client.model.AddSlabRequest;
import com.lms.client.model.FuelSurchargeRequest;
import com.lms.client.model.Tariff;
import com.lms.client.model.TariffCard;
import com.lms.client.model.TariffSlab;
import com.lms.client.model.TariffSlice;
import com.lms.finance.command.TariffCommandService;
import com.lms.finance.command.domain.TariffSlab.RateBasis;
import com.lms.identity.access.SubjectAttributes;
import com.lms.identity.command.domain.AppUser;
import com.lms.identity.security.TokenService;
import com.lms.masterdata.command.FleetCommandService;
import com.lms.masterdata.command.RegisterTerminalCommand;
import com.lms.masterdata.command.TerminalCommandService;
import com.lms.masterdata.command.domain.BusinessPartner;
import com.lms.masterdata.command.domain.Terminal;
import com.lms.shared.geo.LatLon;
import com.lms.shared.tenant.TenantContext;
import feign.FeignException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reading road rate cards back over HTTP, through the client generated from
 * the contract.
 *
 * <p>Cards are published under a head office and two regions beneath it. A
 * card is visible along the reader's line of the organisation: the head
 * office's card applies to a region, so a regional reader sees it; a
 * region's card sits beneath the head office, so a head-office reader sees
 * it; the other region's card is a sibling's and is not found rather than
 * forbidden. A vendor's own staff see only that vendor's cards, and another
 * tenant sees none of them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TariffReadApiTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.7-alpine")
            .withDatabaseName("lms").withUsername("lms").withPassword("lms");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    private static final List<String> MANAGER = List.of("TARIFF_MANAGE");
    private static final List<String> BILL_READER = List.of("INVOICE_READ");

    /** What the in-process setup needs. Never put in a token. */
    private static final List<String> SETUP = List.of(
            "PARTNER_CREATE", "PARTNER_UPDATE", "TERMINAL_CREATE", "TARIFF_MANAGE");

    @LocalServerPort
    private int port;

    @Autowired
    private TestFixtures fixtures;
    @Autowired
    private FleetCommandService fleet;
    @Autowired
    private TerminalCommandService terminals;
    @Autowired
    private TariffCommandService tariffs;
    @Autowired
    private TokenService tokens;

    private String salt;
    private UUID tenantId;
    private String rootPath;
    private UUID rootUnit;
    private UUID northUnit;
    private UUID southUnit;
    private UUID vendor;
    private UUID otherVendor;
    private UUID origin;
    private UUID destination;
    private UUID farDestination;
    private UUID headOfficeCard;
    private UUID northCard;
    private UUID southCard;

    @BeforeEach
    void cardsAcrossTheOrganisation() {
        salt = UUID.randomUUID().toString().substring(0, 8);
        rootPath = "/rates-" + salt + "/";
        tenantId = fixtures.createTenant("rates-" + salt, "Rates " + salt);
        rootUnit = fixtures.createOrgUnit(tenantId, rootPath, "Head office", "HQ", null);
        northUnit = fixtures.createOrgUnit(tenantId, rootPath + "north/", "North", "REGION", rootUnit);
        southUnit = fixtures.createOrgUnit(tenantId, rootPath + "south/", "South", "REGION", rootUnit);
        asSetup(() -> {
            vendor = fleet.registerPartner(rootUnit, "VEN-" + salt, "Road vendor",
                    BusinessPartner.PartnerType.VENDOR, "TAX-V-" + salt);
            otherVendor = fleet.registerPartner(rootUnit, "VEN2-" + salt, "Second vendor",
                    BusinessPartner.PartnerType.VENDOR, "TAX-W-" + salt);
            origin = depot("ORG-" + salt, 20.10, 73.10);
            destination = depot("DST-" + salt, 20.30, 73.30);
            farDestination = depot("FAR-" + salt, 20.50, 73.50);
            headOfficeCard = card(rootUnit, "HQ", vendor, destination, "TRUCK-32FT", 300);
            tariffs.addSlab(headOfficeCard, new BigDecimal("10000"), null, RateBasis.PER_KG,
                    new BigDecimal("3.50"));
            tariffs.addSlab(headOfficeCard, BigDecimal.ZERO, new BigDecimal("10000"),
                    RateBasis.PER_KG, new BigDecimal("4.25"));
            northCard = card(northUnit, "NORTH", vendor, farDestination, "TRUCK-32FT", 200);
            southCard = card(southUnit, "SOUTH", otherVendor, destination, "TRAILER-40FT", 100);
            return null;
        });
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("a head-office reader lists every card newest first, a page at a time")
    void listsEverythingBeneathTheHeadOffice() {
        SettlementApi api = client(rootPath, MANAGER);

        TariffSlice first = api.listTariffs(null, null, null, null, null, null, 2);
        assertThat(first.getItems()).extracting(Tariff::getId).containsExactly(southCard, northCard);
        assertThat(first.getHasMore()).isTrue();

        TariffSlice second = api.listTariffs(null, null, null, null, null, first.getNextCursor(), 2);
        assertThat(second.getItems()).extracting(Tariff::getId).containsExactly(headOfficeCard);
        assertThat(second.getHasMore()).isFalse();
        Tariff hq = second.getItems().get(0);
        assertThat(hq.getVendorCode()).isEqualTo("VEN-" + salt);
        assertThat(hq.getOriginTerminalCode()).isEqualTo("ORG-" + salt);
        assertThat(hq.getOrgUnitName()).isEqualTo("Head office");
        assertThat(hq.getSlabCount()).isEqualTo(2);
        assertThat(hq.getSuperseded()).isFalse();
    }

    @Test
    @DisplayName("a card is read back with its bands, lightest first")
    void readsACardWithItsBands() {
        TariffCard card = client(rootPath, MANAGER).getTariff(headOfficeCard);

        assertThat(card.getCode()).isEqualTo("HQ-" + salt);
        assertThat(card.getVehicleType()).isEqualTo("TRUCK-32FT");
        assertThat(card.getDetentionFreeHours()).isEqualByComparingTo("2");
        assertThat(card.getSlabs()).extracting(TariffSlab::getMinWeightKg)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(BigDecimal.ZERO, new BigDecimal("10000"));
        assertThat(card.getSlabs().get(0).getRateAmount()).isEqualByComparingTo("4.25");
        assertThat(card.getSlabs().get(1).getMaxWeightKg()).as("the open top band").isNull();
        assertThat(card.getSlabs().get(1).getRateBasis()).isEqualTo(TariffSlab.RateBasisEnum.PER_KG);
    }

    @Test
    @DisplayName("the listing narrows to a vendor, a lane end, a vehicle type, or open versions")
    void filters() {
        SettlementApi api = client(rootPath, MANAGER);
        UUID successor = asSetup(() -> card(rootUnit, "HQ2", vendor, destination, "TRUCK-32FT", 10));

        assertThat(api.listTariffs(otherVendor, null, null, null, null, null, null).getItems())
                .extracting(Tariff::getId).containsExactly(southCard);
        assertThat(api.listTariffs(null, null, farDestination, null, null, null, null).getItems())
                .extracting(Tariff::getId).containsExactly(northCard);
        assertThat(api.listTariffs(null, null, null, "TRAILER-40FT", null, null, null).getItems())
                .extracting(Tariff::getId).containsExactly(southCard);
        assertThat(api.listTariffs(null, null, null, null, true, null, null).getItems())
                .as("publishing a successor closes the incumbent")
                .extracting(Tariff::getId).containsExactly(headOfficeCard);
        assertThat(api.listTariffs(null, null, null, null, false, null, null).getItems())
                .extracting(Tariff::getId).containsExactly(successor, southCard, northCard);
        assertThat(api.getTariff(headOfficeCard).getEffectiveTo()).isNotNull();
    }

    @Test
    @DisplayName("a regional reader sees its own cards and the head office's; a sibling region's card is not found")
    void aRegionSeesItsLineOfTheTree() {
        SettlementApi north = client(rootPath + "north/", BILL_READER);

        assertThat(north.listTariffs(null, null, null, null, null, null, null).getItems())
                .as("its own card and the head office's, which applies to it")
                .extracting(Tariff::getId).containsExactly(northCard, headOfficeCard);
        assertThat(north.getTariff(headOfficeCard).getSlabs()).hasSize(2);
        assertThat(north.getTariff(northCard).getCode()).isEqualTo("NORTH-" + salt);
        assertNotFound(() -> north.getTariff(southCard));
    }

    @Test
    @DisplayName("a card beneath the reader is visible, and so is every card above it; a sibling's is not")
    void childAndAncestorCardsAreVisible() {
        String eastPath = rootPath + "north/east/";
        UUID eastUnit = fixtures.createOrgUnit(tenantId, eastPath, "North east", "BRANCH", northUnit);
        UUID eastCard = asSetup(() -> card(eastUnit, "EAST", vendor, destination, "TRAILER-40FT", 50));

        assertThat(client(rootPath + "north/", MANAGER).listTariffs(null, null, null, null, null, null, null)
                .getItems())
                .as("a card beneath the region is in the subtree it may publish into")
                .extracting(Tariff::getId).containsExactly(eastCard, northCard, headOfficeCard);

        SettlementApi east = client(tenantId, eastUnit, eastPath, BILL_READER);
        assertThat(east.listTariffs(null, null, null, null, null, null, null).getItems())
                .as("its own card, its region's and its head office's")
                .extracting(Tariff::getId).containsExactly(eastCard, northCard, headOfficeCard);
        assertNotFound(() -> east.getTariff(southCard));
        assertNotFound(() -> client(rootPath + "south/", BILL_READER).getTariff(eastCard));
    }

    @Test
    @DisplayName("a vendor's own staff see only that vendor's cards, wherever they sit")
    void vendorStaffSeeOnlyTheirVendorsCards() {
        SettlementApi staff = client(tenantId, rootUnit, rootPath, BILL_READER,
                new SubjectAttributes(null, null, otherVendor, null));

        assertThat(staff.listTariffs(null, null, null, null, null, null, null).getItems())
                .extracting(Tariff::getId).containsExactly(southCard);
        assertThat(staff.getTariff(southCard).getVendorPartnerId()).isEqualTo(otherVendor);
        assertNotFound(() -> staff.getTariff(headOfficeCard));
        assertThat(staff.listTariffs(vendor, null, null, null, null, null, null).getItems())
                .as("asking for another vendor's cards finds none").isEmpty();
    }

    @Test
    @DisplayName("an unknown card, and another tenant's, are not found and not listed")
    void otherTenantsAndUnknownCards() {
        assertNotFound(() -> client(rootPath, MANAGER).getTariff(UUID.randomUUID()));

        String otherSalt = UUID.randomUUID().toString().substring(0, 8);
        String otherPath = "/rates-" + otherSalt + "/";
        UUID otherTenant = fixtures.createTenant("rates-" + otherSalt, "Other " + otherSalt);
        UUID otherUnit = fixtures.createOrgUnit(otherTenant, otherPath, "Other HQ", "HQ", null);
        SettlementApi stranger = client(otherTenant, otherUnit, otherPath, MANAGER);

        assertThat(stranger.listTariffs(null, null, null, null, null, null, null).getItems()).isEmpty();
        assertNotFound(() -> stranger.getTariff(headOfficeCard));
    }

    @Test
    @DisplayName("reading cards needs the right to manage them or to read freight bills")
    void readingNeedsARight() {
        SettlementApi planner = client(rootPath, List.of("ORDER_READ"));

        assertThatThrownBy(() -> planner.listTariffs(null, null, null, null, null, null, null))
                .isInstanceOfSatisfying(FeignException.class, e -> assertThat(e.status()).isEqualTo(403));
        assertThatThrownBy(() -> planner.getTariff(headOfficeCard))
                .isInstanceOfSatisfying(FeignException.class, e -> assertThat(e.status()).isEqualTo(403));
        assertThat(client(rootPath, BILL_READER).getTariff(headOfficeCard).getId())
                .isEqualTo(headOfficeCard);
    }

    @Test
    @DisplayName("a cursor the listing never issued is refused with a code")
    void foreignCursor() {
        assertThatThrownBy(() -> client(rootPath, MANAGER)
                .listTariffs(null, null, null, null, null, "not-a-cursor", 5))
                .isInstanceOfSatisfying(FeignException.class,
                        e -> assertThat(e.contentUTF8()).contains("invalid-cursor"));
    }

    @Test
    @DisplayName("a band is added only to a card in the caller's subtree, as publishing one is")
    void addingABandIsHeldToTheCardsUnit() {
        SettlementApi north = client(rootPath + "north/", MANAGER);
        AddSlabRequest band = new AddSlabRequest().minWeightKg(BigDecimal.ZERO)
                .rateBasis(AddSlabRequest.RateBasisEnum.PER_KG).rateAmount(new BigDecimal("2.00"));

        assertForbidden(() -> north.addTariffSlab(southCard, band, null));
        assertThat(client(rootPath + "south/", MANAGER).getTariff(southCard).getSlabs())
                .as("the refused band was not written").isEmpty();
        assertThat(north.addTariffSlab(northCard, band, null).getId()).as("its own unit's card").isNotNull();
        assertThat(client(rootPath, MANAGER).addTariffSlab(southCard, band, null).getId())
                .as("the head office reaches every unit beneath it").isNotNull();
    }

    @Test
    @DisplayName("the tenant's fuel index is changed only by a caller whose reach is the whole organisation")
    void fuelIndexIsTheWholeTenants() {
        FuelSurchargeRequest index = new FuelSurchargeRequest().month(LocalDate.now())
                .surchargePct(new BigDecimal("6.5"));

        assertForbidden(() -> client(rootPath + "north/", MANAGER).recordFuelSurcharge(index, null));
        assertThat(client(rootPath, MANAGER).recordFuelSurcharge(index, null).getId()).isNotNull();
    }

    // ------------------------------------------------------------- helpers

    /**
     * Publishes a card, back-dated so that successive cards in one test are
     * created in order and a successor can start after its incumbent.
     */
    private UUID card(UUID orgUnit, String code, UUID vendorId, UUID to, String vehicleType,
                      int daysAgo) {
        UUID id = tariffs.publishTariff(orgUnit, code + "-" + salt, vendorId, origin, to,
                vehicleType, "INR", LocalDate.now().minusDays(daysAgo), new BigDecimal("2"),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        pause();
        return id;
    }

    /** Creation instants order the listing; keep consecutive cards a tick apart. */
    private static void pause() {
        try {
            Thread.sleep(5);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void assertForbidden(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(FeignException.class,
                e -> assertThat(e.status()).isEqualTo(403));
    }

    private static void assertNotFound(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(FeignException.class, e -> {
            assertThat(e.status()).isEqualTo(404);
            assertThat(e.contentUTF8()).contains("not-found");
        });
    }

    private SettlementApi client(String orgPath, List<String> permissions) {
        UUID unit = orgPath.equals(rootPath) ? rootUnit
                : orgPath.endsWith("north/") ? northUnit : southUnit;
        return client(tenantId, unit, orgPath, permissions);
    }

    private SettlementApi client(UUID tenant, UUID unit, String orgPath, List<String> permissions) {
        return client(tenant, unit, orgPath, permissions, SubjectAttributes.NONE);
    }

    private SettlementApi client(UUID tenant, UUID unit, String orgPath, List<String> permissions,
                                 SubjectAttributes attributes) {
        String email = "reader-" + UUID.randomUUID().toString().substring(0, 8) + "@rates.test";
        UUID id = fixtures.createUser(tenant, unit, orgPath, email, "not-used-here");
        AppUser user = new AppUser(id, tenant, unit, email, "not-used", email,
                AppUser.UserStatus.ACTIVE, null, 0, null, null, null, Instant.now(), Instant.now());
        String token = tokens.issueAccessToken(user, orgPath, permissions, attributes).value();
        ApiClient apiClient = new ApiClient().setBasePath("http://localhost:" + port);
        apiClient.getFeignBuilder().requestInterceptor(template ->
                template.header("Authorization", "Bearer " + token));
        return apiClient.buildClient(SettlementApi.class);
    }

    private UUID depot(String code, double lat, double lon) {
        return terminals.register(new RegisterTerminalCommand.PointRadius(rootUnit, code, code,
                Terminal.FunctionalCategory.CUSTOMER_SITE, new LatLon(lat, lon), 500, 2, null,
                null, 45, List.of()));
    }

    /** Runs setup scoped to the tenant's head office, signed in with what the setup needs. */
    private <T> T asSetup(Supplier<T> action) {
        Set<SimpleGrantedAuthority> authorities = SETUP.stream()
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toSet());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("setup", "n/a", authorities));
        try {
            return TenantContext.callWith(tenantId, rootPath, action);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
