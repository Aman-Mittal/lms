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

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.lms.client.api.ComplianceApi;
import com.lms.client.api.DriversApi;
import com.lms.client.api.PartnersApi;
import com.lms.client.api.VehiclesApi;
import com.lms.client.model.AttachDocumentRequest;
import com.lms.client.model.ComplianceDocument;
import com.lms.client.model.IdResponse;
import com.lms.client.model.RegisterPartnerRequest;
import com.lms.client.model.RegisterVehicleRequest;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.LinkedHashMap;

import static com.lms.e2e.DirectorySupport.refusedWith;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Statutory documents over HTTP: filed against partners, vehicles and drivers,
 * listed owner by owner, and swept by the expiry watch that spans all three.
 *
 * <p>The watch is tenant-wide and the demo tenant is shared, so every
 * assertion is about the ids this scenario filed: which of them the watch
 * returns, in what order, and which it leaves out. Nothing depends on what
 * else is about to lapse.
 */
public class DirectoryDocumentSteps {

    private static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);

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

    private UUID partner;
    private UUID lorry;
    private UUID driver;
    private String lorryReg;
    private final Map<String, UUID> filed = new LinkedHashMap<>();

    @Given("the document directory is open to the demo administrator, a second tenant, a read-only user and a user with no permissions")
    public void open() {
        world = new HttpWorld(port);
        world.signInAsDemoAdministrator();
        support = new DirectorySupport(jdbc, transactionManager, passwordEncoder, port);
        cast = support.cast(world);
    }

    private AttachDocumentRequest certificate(String type, String number, Integer expiresInDays) {
        AttachDocumentRequest request = new AttachDocumentRequest().documentType(type)
                .documentNo(number + "-" + world.salt).issuingAuthority("RTA " + type);
        if (expiresInDays != null) {
            request.issuedOn(TODAY.minusYears(2)).expiresOn(TODAY.plusDays(expiresInDays));
        }
        return request;
    }

    private void owners() {
        partner = world.partner("DOCP", RegisterPartnerRequest.PartnerTypeEnum.VENDOR);
        lorry = world.lorry("DOC" + world.salt, 9000);
        lorryReg = "MH-DOC" + world.salt + "-" + world.salt;
        driver = world.driver("DOC");
    }

    // ------------------------------------------------- owner by owner

    @When("certificates are filed against a partner, a lorry and a driver: one lapsed and one due in ten days")
    public void fileForEachOwner() {
        owners();
        PartnersApi partners = world.api(PartnersApi.class);
        VehiclesApi vehicles = world.api(VehiclesApi.class);
        DriversApi drivers = world.api(DriversApi.class);
        for (String kind : List.of("partner", "lorry", "driver")) {
            AttachDocumentRequest lapsed = certificate("PERMIT", "LAPSED-" + kind, -5);
            AttachDocumentRequest soon = certificate("FITNESS", "SOON-" + kind, 10);
            IdResponse a;
            IdResponse b;
            switch (kind) {
                case "partner" -> {
                    a = partners.attachPartnerDocument(partner, lapsed, null);
                    b = partners.attachPartnerDocument(partner, soon, null);
                }
                case "lorry" -> {
                    a = vehicles.attachVehicleDocument(lorry, lapsed, null);
                    b = vehicles.attachVehicleDocument(lorry, soon, null);
                }
                default -> {
                    a = drivers.attachDriverDocument(driver, lapsed, null);
                    b = drivers.attachDriverDocument(driver, soon, null);
                }
            }
            filed.put("lapsed-" + kind, a.getId());
            filed.put("soon-" + kind, b.getId());
        }
    }

    @Then("each owner's documents are listed soonest first, labelled with the owner's name")
    public void listedOwnerByOwner() {
        assertListed(world.api(PartnersApi.class).listPartnerDocuments(partner), "partner", partner,
                ComplianceDocument.OwnerTypeEnum.PARTNER, "DOCP " + world.salt + " Pvt Ltd");
        assertListed(world.api(DriversApi.class).listDriverDocuments(driver), "driver", driver,
                ComplianceDocument.OwnerTypeEnum.DRIVER, "Driver DOC");

        // The lorry already carries the insurance filed when it was registered.
        List<ComplianceDocument> onLorry = world.api(VehiclesApi.class).listVehicleDocuments(lorry);
        assertThat(onLorry).extracting(ComplianceDocument::getDocumentType)
                .containsExactly("PERMIT", "FITNESS", "INSURANCE");
        assertThat(onLorry).extracting(ComplianceDocument::getOwnerLabel).containsOnly(lorryReg);
        assertThat(onLorry).extracting(ComplianceDocument::getId).contains(
                filed.get("lapsed-lorry"), filed.get("soon-lorry"));
    }

    private void assertListed(List<ComplianceDocument> documents, String kind, UUID owner,
                              ComplianceDocument.OwnerTypeEnum ownerType, String label) {
        assertThat(documents).extracting(ComplianceDocument::getId).containsExactly(
                filed.get("lapsed-" + kind), filed.get("soon-" + kind));

        ComplianceDocument lapsed = documents.get(0);
        assertThat(lapsed.getOwnerType()).isEqualTo(ownerType);
        assertThat(lapsed.getOwnerId()).isEqualTo(owner);
        assertThat(lapsed.getOwnerLabel()).isEqualTo(label);
        assertThat(lapsed.getDocumentType()).isEqualTo("PERMIT");
        assertThat(lapsed.getDocumentNo()).isEqualTo("LAPSED-" + kind + "-" + world.salt);
        assertThat(lapsed.getIssuingAuthority()).isEqualTo("RTA PERMIT");
        assertThat(lapsed.getIssuedOn()).isEqualTo(TODAY.minusYears(2));
        assertThat(lapsed.getExpiresOn()).isEqualTo(TODAY.minusDays(5));
        assertThat(lapsed.getExpired()).isTrue();
        assertThat(lapsed.getDaysToExpiry()).isEqualTo(-5);

        ComplianceDocument soon = documents.get(1);
        assertThat(soon.getExpired()).isFalse();
        assertThat(soon.getDaysToExpiry()).isEqualTo(10);
        assertThat(soon.getExpiresOn()).isEqualTo(TODAY.plusDays(10));
    }

    @Then("the partner and the lorry each count one expired document and the lorry shows the lapsed date as its earliest expiry")
    public void summariesCountTheLapse() {
        assertThat(world.api(PartnersApi.class).getPartner(partner).getExpiredDocumentCount()).isEqualTo(1);
        var vehicle = world.api(VehiclesApi.class).getVehicle(lorry);
        assertThat(vehicle.getExpiredDocumentCount()).isEqualTo(1);
        assertThat(vehicle.getEarliestDocumentExpiry()).isEqualTo(TODAY.minusDays(5));
        // Each owner's list holds only its own certificates.
        assertThat(world.api(PartnersApi.class).listPartnerDocuments(partner))
                .extracting(ComplianceDocument::getId)
                .doesNotContain(filed.get("lapsed-lorry"), filed.get("lapsed-driver"));
    }

    // ---------------------------------------------------- expiry watch

    @When("certificates lapsing at known distances are filed against a lorry, a driver and a partner")
    public void fileAtKnownDistances() {
        owners();
        VehiclesApi vehicles = world.api(VehiclesApi.class);
        filed.put("lapsed", vehicles.attachVehicleDocument(lorry,
                certificate("PUC", "W-LAPSED", -3), null).getId());
        filed.put("boundary", vehicles.attachVehicleDocument(lorry,
                certificate("PUC", "W-BOUNDARY", 30), null).getId());
        filed.put("beyond", vehicles.attachVehicleDocument(lorry,
                certificate("PUC", "W-BEYOND", 31), null).getId());
        filed.put("permanent", vehicles.attachVehicleDocument(lorry,
                certificate("PUC", "W-PERMANENT", null), null).getId());
        filed.put("driver", world.api(DriversApi.class).attachDriverDocument(driver,
                certificate("MEDICAL", "W-DRIVER", 10), null).getId());
        filed.put("partner", world.api(PartnersApi.class).attachPartnerDocument(partner,
                certificate("GST", "W-PARTNER", 45), null).getId());
    }

    private List<ComplianceDocument> mine(List<ComplianceDocument> watch) {
        return watch.stream().filter(d -> filed.containsValue(d.getId())).toList();
    }

    @Then("the expiry watch lists the lapsed, the soon and the thirty-day certificates, soonest first, and no others")
    public void defaultWindow() {
        ComplianceApi api = world.api(ComplianceApi.class);
        for (List<ComplianceDocument> watch : List.of(api.listExpiringDocuments((Integer) null),
                api.listExpiringDocuments(30))) {
            List<ComplianceDocument> found = mine(watch);
            assertThat(found).extracting(ComplianceDocument::getId)
                    .as("lapsed, then ten days, then exactly thirty days; not 31, 45 or never")
                    .containsExactly(filed.get("lapsed"), filed.get("driver"), filed.get("boundary"));

            ComplianceDocument lapsed = found.get(0);
            assertThat(lapsed.getOwnerType()).isEqualTo(ComplianceDocument.OwnerTypeEnum.VEHICLE);
            assertThat(lapsed.getOwnerId()).isEqualTo(lorry);
            assertThat(lapsed.getOwnerLabel()).isEqualTo(lorryReg);
            assertThat(lapsed.getExpired()).isTrue();
            assertThat(lapsed.getDaysToExpiry()).isEqualTo(-3);
            assertThat(lapsed.getDocumentNo()).isEqualTo("W-LAPSED-" + world.salt);

            ComplianceDocument medical = found.get(1);
            assertThat(medical.getOwnerType()).isEqualTo(ComplianceDocument.OwnerTypeEnum.DRIVER);
            assertThat(medical.getOwnerId()).isEqualTo(driver);
            assertThat(medical.getOwnerLabel()).isEqualTo("Driver DOC");
            assertThat(medical.getExpired()).isFalse();
            assertThat(medical.getDaysToExpiry()).isEqualTo(10);

            assertThat(found.get(2).getDaysToExpiry()).isEqualTo(30);
        }
    }

    @Then("widening the window to sixty days adds the later certificates and a window of zero keeps only what has lapsed")
    public void otherWindows() {
        ComplianceApi api = world.api(ComplianceApi.class);
        assertThat(mine(api.listExpiringDocuments(60))).extracting(ComplianceDocument::getId)
                .containsExactly(filed.get("lapsed"), filed.get("driver"), filed.get("boundary"),
                        filed.get("beyond"), filed.get("partner"));
        List<ComplianceDocument> partnerRow = mine(api.listExpiringDocuments(60)).stream()
                .filter(d -> d.getId().equals(filed.get("partner"))).toList();
        assertThat(partnerRow.get(0).getOwnerType()).isEqualTo(ComplianceDocument.OwnerTypeEnum.PARTNER);
        assertThat(partnerRow.get(0).getOwnerLabel()).isEqualTo("DOCP " + world.salt + " Pvt Ltd");

        assertThat(mine(api.listExpiringDocuments(0))).extracting(ComplianceDocument::getId)
                .containsExactly(filed.get("lapsed"));
        assertThat(mine(api.listExpiringDocuments(3650))).extracting(ComplianceDocument::getId)
                .as("a permanent certificate never lapses, whatever the window")
                .doesNotContain(filed.get("permanent"));
    }

    @Then("another tenant's certificates never appear in the watch and ours never appear in theirs")
    public void watchIsTenantScoped() {
        VehiclesApi theirVehicles = world.client(cast.otherAdmin().getAccessToken(), VehiclesApi.class);
        UUID theirLorry = theirVehicles.registerVehicle(new RegisterVehicleRequest()
                .orgUnitId(cast.otherAdmin().getOrgUnitId()).registrationNo("DOCTHEIRS-" + world.salt)
                .category("TRUCK").vehicleType("RIGID").axleConfig("2-AXLE")
                .grossWeightKg(new BigDecimal("16000")).tareWeightKg(new BigDecimal("6000"))
                .maxVolumeM3(new BigDecimal("40")).hazmatCertified(false).reeferCapable(false),
                null).getId();
        UUID theirDocument = theirVehicles.attachVehicleDocument(theirLorry,
                certificate("PUC", "W-THEIRS", 1), null).getId();

        List<UUID> theirWatch = world.client(cast.otherAdmin().getAccessToken(), ComplianceApi.class)
                .listExpiringDocuments(30).stream().map(ComplianceDocument::getId).toList();
        assertThat(theirWatch).contains(theirDocument).doesNotContainAnyElementsOf(filed.values());

        assertThat(world.api(ComplianceApi.class).listExpiringDocuments(30))
                .extracting(ComplianceDocument::getId).doesNotContain(theirDocument);
    }

    @Then("the expiry watch is refused as 403 to a user with no permissions and open to a read-only user")
    public void watchPermissions() {
        refusedWith(403, "access-denied", () -> world.client(cast.nobody().getAccessToken(),
                ComplianceApi.class).listExpiringDocuments(30));
        assertThat(mine(world.client(cast.reader().getAccessToken(), ComplianceApi.class)
                .listExpiringDocuments(30))).extracting(ComplianceDocument::getId)
                .contains(filed.get("lapsed"), filed.get("driver"), filed.get("boundary"));
    }

    // ------------------------------------------------------ attaching

    @Given("a partner, a lorry and a driver are registered to file documents against")
    public void owners_registered() {
        owners();
    }

    @Then("a certificate with no type or no number is a 400 problem naming the field, for each kind of owner")
    public void invalidCertificates() {
        AttachDocumentRequest noType = new AttachDocumentRequest().documentType(" ").documentNo("N-" + world.salt);
        AttachDocumentRequest noNumber = new AttachDocumentRequest().documentType("PERMIT").documentNo("");

        PartnersApi partners = world.api(PartnersApi.class);
        VehiclesApi vehicles = world.api(VehiclesApi.class);
        DriversApi drivers = world.api(DriversApi.class);
        List<JsonNode> problems = new ArrayList<>();
        problems.add(refusedWith(400, "validation-failed", () -> partners.attachPartnerDocument(partner, noType, null)));
        problems.add(refusedWith(400, "validation-failed", () -> vehicles.attachVehicleDocument(lorry, noType, null)));
        problems.add(refusedWith(400, "validation-failed", () -> drivers.attachDriverDocument(driver, noType, null)));
        for (JsonNode problem : problems) {
            assertThat(problem.get("errors").has("documentType")).isTrue();
            assertThat(problem.get("errors").has("documentNo")).isFalse();
        }
        JsonNode number = refusedWith(400, "validation-failed",
                () -> vehicles.attachVehicleDocument(lorry, noNumber, null));
        assertThat(number.get("errors").has("documentNo")).isTrue();

        assertThat(partners.listPartnerDocuments(partner)).as("nothing was filed").isEmpty();
        assertThat(drivers.listDriverDocuments(driver)).as("nothing was filed").isEmpty();
    }

    @Then("filing a certificate is refused as 403 to a read-only user and to a user with no permissions, for each kind of owner")
    public void filingNeedsUpdatePermission() {
        AttachDocumentRequest request = certificate("PERMIT", "DENIED", 100);
        for (String token : List.of(cast.reader().getAccessToken(), cast.nobody().getAccessToken())) {
            refusedWith(403, "access-denied", () -> world.client(token, PartnersApi.class)
                    .attachPartnerDocument(partner, request, null));
            refusedWith(403, "access-denied", () -> world.client(token, VehiclesApi.class)
                    .attachVehicleDocument(lorry, request, null));
            refusedWith(403, "access-denied", () -> world.client(token, DriversApi.class)
                    .attachDriverDocument(driver, request, null));
        }
        assertThat(world.api(PartnersApi.class).listPartnerDocuments(partner)).isEmpty();
        assertThat(world.api(DriversApi.class).listDriverDocuments(driver)).isEmpty();
    }

    @Then("a certificate filed twice under one idempotency key is stored once, and the key refuses a different certificate")
    public void idempotentFiling() {
        PartnersApi partners = world.api(PartnersApi.class);
        AttachDocumentRequest request = certificate("GST", "ONCE", 200);
        String key = "doc-" + world.salt;

        UUID first = partners.attachPartnerDocument(partner, request, key).getId();
        UUID replay = partners.attachPartnerDocument(partner, request, key).getId();
        assertThat(replay).as("the replay answers with the first filing").isEqualTo(first);
        assertThat(partners.listPartnerDocuments(partner)).extracting(ComplianceDocument::getId)
                .containsExactly(first);

        refusedWith(409, "idempotency-key-reused", () -> partners.attachPartnerDocument(partner,
                certificate("GST", "OTHER", 200), key));
        assertThat(partners.listPartnerDocuments(partner)).hasSize(1);
    }

    @Then("a certificate with only a type, a number and an expiry is accepted and reads back without issue date or authority")
    public void minimalCertificate() {
        DriversApi drivers = world.api(DriversApi.class);
        UUID id = drivers.attachDriverDocument(driver, new AttachDocumentRequest()
                .documentType("BADGE").documentNo("B-" + world.salt)
                .expiresOn(TODAY.plusDays(90)), null).getId();
        List<ComplianceDocument> documents = drivers.listDriverDocuments(driver);
        assertThat(documents).hasSize(1);
        ComplianceDocument badge = documents.get(0);
        assertThat(badge.getId()).isEqualTo(id);
        assertThat(badge.getDocumentType()).isEqualTo("BADGE");
        assertThat(badge.getIssuingAuthority()).isNull();
        assertThat(badge.getIssuedOn()).isNull();
        assertThat(badge.getExpiresOn()).isEqualTo(TODAY.plusDays(90));
        assertThat(badge.getDaysToExpiry()).isEqualTo(90);
        assertThat(badge.getExpired()).isFalse();
    }

    @Then("a certificate with no expiry date is listed for a partner, a lorry and a driver as not expired, with no days to expiry")
    public void certificateWithoutExpiry() {
        AttachDocumentRequest open = new AttachDocumentRequest()
                .documentType("LICENCE").documentNo("NX-" + world.salt);
        UUID onPartner = world.api(PartnersApi.class).attachPartnerDocument(partner, open, null).getId();
        UUID onLorry = world.api(VehiclesApi.class).attachVehicleDocument(lorry, open, null).getId();
        UUID onDriver = world.api(DriversApi.class).attachDriverDocument(driver, open, null).getId();

        assertNoExpiry(world.api(PartnersApi.class).listPartnerDocuments(partner), onPartner);
        assertNoExpiry(world.api(VehiclesApi.class).listVehicleDocuments(lorry), onLorry);
        assertNoExpiry(world.api(DriversApi.class).listDriverDocuments(driver), onDriver);
        // It has nothing to lapse, so the expiry watch never lists it.
        assertThat(world.api(ComplianceApi.class).listExpiringDocuments(3650))
                .extracting(ComplianceDocument::getId)
                .doesNotContain(onPartner, onLorry, onDriver);
    }

    private static void assertNoExpiry(List<ComplianceDocument> documents, UUID id) {
        ComplianceDocument document = documents.stream().filter(d -> id.equals(d.getId())).findFirst()
                .orElseThrow(() -> new AssertionError("document " + id + " is not listed"));
        assertThat(document.getExpired()).isFalse();
        assertThat(document.getExpiresOn()).isNull();
        assertThat(document.getDaysToExpiry()).isNull();
    }
}
