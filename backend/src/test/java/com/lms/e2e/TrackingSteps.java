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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.lms.client.api.AuthenticationApi;
import com.lms.client.api.DeliveryAndClaimsApi;
import com.lms.client.api.PublicTrackingApi;
import com.lms.client.api.TelematicsApi;
import com.lms.client.api.TrackingLinksApi;
import com.lms.client.model.IssueTrackingLinkRequest;
import com.lms.client.model.LoginRequest;
import com.lms.client.model.Ping;
import com.lms.client.model.PingBatchRequest;
import com.lms.client.model.PublicTracking;
import com.lms.client.model.RecordDeliveryProofRequest;
import com.lms.client.model.RegisterPartnerRequest;
import com.lms.client.model.RegisterPointRadiusTerminalRequest;
import com.lms.client.model.TrackingLinkIssued;
import feign.FeignException;
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
 * Tracking links over HTTP, against a consignment carried by a trip that really
 * was assembled and dispatched through the published API.
 *
 * <p>The customer's side is a client with no token at all: the point of the
 * feature is that the link is the only credential. Latitude band 26 belongs to
 * this feature alone, so its terminals cannot overlap another mode's.
 */
public class TrackingSteps {

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
    private UUID consignment;
    private UUID lorry;
    private TrackingLinkIssued link;
    private TrackingLinksApi secondApi;

    private TrackingLinksApi desk() {
        return world.api(TrackingLinksApi.class);
    }

    private PublicTrackingApi customer() {
        return world.client(null, PublicTrackingApi.class);
    }

    // ------------------------------------------------------------ session

    @Given("the demo administrator is signed in for tracking")
    public void signIn() {
        world = new HttpWorld(port);
        world.signInAsDemoAdministrator();
    }

    @Given("a consignment is in transit for tracking")
    public void inTransit() {
        var site = RegisterPointRadiusTerminalRequest.CategoryEnum.CUSTOMER_SITE;
        UUID origin = world.terminal(site, "TK-ORIGIN", 26.10, 72.90);
        UUID destination = world.terminal(site, "TK-DEST", 26.30, 73.10);
        UUID vendor = world.activeVendor("TKV");
        UUID customerPartner = world.partner("TKC", RegisterPartnerRequest.PartnerTypeEnum.CUSTOMER);
        UUID consignee = world.partner("TKN", RegisterPartnerRequest.PartnerTypeEnum.CONSIGNEE);
        lorry = world.lorry("TK", 20000);
        UUID driver = world.driver("TK");
        UUID trip = world.assignedTrip("TK", customerPartner, consignee, origin, destination, vendor,
                lorry, driver, 5000);
        world.dispatch(trip, origin, 5000, OffsetDateTime.now(ZoneOffset.UTC).minusHours(5));

        // The consignment itself stays ASSIGNED_TO_LOAD for a full truckload; what
        // says it has left is the trip, which dispatch has just moved.
        consignment = jdbc.sql("""
                        SELECT c.id FROM consignment c JOIN sales_order o ON o.id = c.order_id
                         WHERE o.order_no = :no
                        """).param("no", "SO-TK-" + world.salt).query(UUID.class).single();
    }

    @Given("the vehicle reports a position of {bigdecimal} and {bigdecimal}")
    public void vehicleReports(BigDecimal lat, BigDecimal lon) {
        var result = world.api(TelematicsApi.class).ingestPings(new PingBatchRequest().pings(List.of(
                new Ping().vehicleId(lorry).recordedAt(OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(5))
                        .lat(lat).lon(lon.add(world.shiftedLon(0)))
                        .speedKph(new BigDecimal("60")).accuracyM(new BigDecimal("5"))
                        .ignitionOn(true).source("DEVICE"))), null);
        assertThat(result.getAccepted()).as("the ping was taken in").isEqualTo(1);
    }

    // -------------------------------------------------------------- the desk

    // One keyword only: Given, When and Then match interchangeably, so this serves all three.
    @When("the desk issues a tracking link")
    public void issue() {
        link = desk().issueTrackingLink(consignment, null, new IssueTrackingLinkRequest());
        assertThat(link.getToken()).as("the token is shown once, here").isNotBlank();
    }

    @When("the desk revokes the tracking link")
    public void revoke() {
        desk().revokeTrackingLink(link.getId(), null);
    }

    @When("the tracking link passes its expiry")
    public void expire() {
        jdbc.sql("""
                        UPDATE tracking_link SET created_at = now() - interval '2 days',
                                                 expires_at = now() - interval '1 day'
                         WHERE id = :id
                        """).param("id", link.getId()).update();
    }

    @When("the delivery is proved by recipient {string}")
    public void proved(String recipient) {
        DeliveryAndClaimsApi delivery = world.api(DeliveryAndClaimsApi.class);
        String code = delivery.issueDeliveryOtp(consignment, null).getOtp();
        delivery.recordDeliveryProof(consignment,
                new RecordDeliveryProofRequest().otp(code).recipientName(recipient), null);
    }

    @Then("the desk's list shows one active link and never the token")
    public void listShowsOne() {
        var links = desk().listTrackingLinks(consignment).getItems();
        assertThat(links).hasSize(1);
        assertThat(links.get(0).getStatus().getValue()).isEqualTo("ACTIVE");
        assertThat(links.toString()).doesNotContain(link.getToken());
    }

    // ---------------------------------------------------------- the customer

    @Then("the customer sees the consignment in transit from {string} to {string} with no proof of delivery")
    public void seesInTransit(String origin, String destination) {
        PublicTracking view = customer().getPublicTracking(link.getToken());
        assertThat(view.getStatus()).isEqualTo(PublicTracking.StatusEnum.IN_TRANSIT);
        assertThat(view.getOriginName()).isEqualTo(origin);
        assertThat(view.getDestinationName()).isEqualTo(destination);
        assertThat(view.getProofRecorded()).isFalse();
        assertThat(view.getReference()).isNotBlank();
    }

    @Then("the customer sees the position {bigdecimal} and {bigdecimal}")
    public void seesPosition(BigDecimal lat, BigDecimal lon) {
        var position = customer().getPublicTracking(link.getToken()).getPosition();
        assertThat(position).as("a vehicle in transit reports where it is").isNotNull();
        assertThat(position.getLat()).isEqualByComparingTo(lat);
        assertThat(position.getLon().subtract(world.shiftedLon(0))).isEqualByComparingTo(lon);
    }

    @Then("the customer sees the consignment delivered with a proof of delivery")
    public void seesDelivered() {
        PublicTracking view = customer().getPublicTracking(link.getToken());
        assertThat(view.getStatus()).isEqualTo(PublicTracking.StatusEnum.DELIVERED);
        assertThat(view.getProofRecorded()).isTrue();
        assertThat(view.getDeliveredAt()).isNotNull();
        assertThat(view.getPosition()).as("it has arrived").isNull();
    }

    @Then("the customer is not told who received it")
    public void notToldWho() {
        assertThat(customer().getPublicTracking(link.getToken()).toString()).doesNotContain("Asha");
    }

    @Then("the customer is told the link is not valid, as for a link nobody issued")
    public void notValid() {
        FeignException nonsense = refusal("a-link-nobody-ever-issued");
        FeignException ours = refusal(link.getToken());
        assertThat(ours.status()).isEqualTo(404);
        assertThat(ours.contentUTF8()).contains("tracking-link-not-found");
        assertThat(ours.contentUTF8()).as("no tell-tale difference").isEqualTo(nonsense.contentUTF8());
    }

    private FeignException refusal(String token) {
        try {
            customer().getPublicTracking(token);
        } catch (FeignException e) {
            return e;
        }
        throw new AssertionError("the link was accepted");
    }

    // ------------------------------------------------------- second company

    @Given("a second company with its own administrator is signed in for tracking")
    public void secondCompany() {
        ContractSupport support = new ContractSupport(port, jdbc, passwordEncoder, transactionManager, jwtEncoder);
        ContractSupport.OtherTenant other = support.provisionTenant(world.salt);
        String token = world.client(null, AuthenticationApi.class).login(new LoginRequest()
                .tenantCode(other.code()).email(other.email()).password(ContractSupport.PASSWORD))
                .getAccessToken();
        secondApi = world.client(token, TrackingLinksApi.class);
    }

    @Then("the second company is told the consignment does not exist when it issues a link")
    public void secondCannotIssue() {
        assertThatThrownBy(() -> secondApi.issueTrackingLink(consignment, null, new IssueTrackingLinkRequest()))
                .isInstanceOfSatisfying(FeignException.class, e -> assertThat(e.status()).isEqualTo(404));
    }

    @Then("the second company is told the link does not exist when it revokes it")
    public void secondCannotRevoke() {
        assertThatThrownBy(() -> secondApi.revokeTrackingLink(link.getId(), null))
                .isInstanceOfSatisfying(FeignException.class, e -> assertThat(e.status()).isEqualTo(404));
        assertThat(customer().getPublicTracking(link.getToken()).getReference())
                .as("the link still works").isNotBlank();
    }
}
