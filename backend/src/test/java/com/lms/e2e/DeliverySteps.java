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
import com.lms.client.model.Claim;
import com.lms.client.model.ClaimStatus;
import com.lms.client.model.ClaimType;
import com.lms.client.model.DeliveryProof;
import com.lms.client.model.LoginRequest;
import com.lms.client.model.RaiseClaimRequest;
import com.lms.client.model.RecordDeliveryProofRequest;
import com.lms.client.model.RegisterPartnerRequest;
import com.lms.client.model.RegisterPointRadiusTerminalRequest;
import com.lms.client.model.TransitionClaimRequest;
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
 * Proof of delivery and claims over HTTP, against a consignment carried by a
 * trip that really was assembled and dispatched through the published API.
 *
 * <p>Latitude band 23 belongs to this feature alone, so its terminals cannot
 * overlap another mode's.
 */
public class DeliverySteps {

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
    private String code;
    private UUID claim;
    private FeignException refusal;
    private DeliveryAndClaimsApi secondApi;

    private DeliveryAndClaimsApi desk() {
        return world.api(DeliveryAndClaimsApi.class);
    }

    // ------------------------------------------------------------ session

    @Given("the demo administrator is signed in at the delivery desk")
    public void signIn() {
        world = new HttpWorld(port);
        world.signInAsDemoAdministrator();
    }

    @Given("a consignment is in transit for delivery")
    public void inTransit() {
        var site = RegisterPointRadiusTerminalRequest.CategoryEnum.CUSTOMER_SITE;
        UUID origin = world.terminal(site, "DL-ORIGIN", 23.10, 72.90);
        UUID destination = world.terminal(site, "DL-DEST", 23.30, 73.10);
        UUID vendor = world.activeVendor("DLV");
        UUID customer = world.partner("DLC", RegisterPartnerRequest.PartnerTypeEnum.CUSTOMER);
        UUID consignee = world.partner("DLN", RegisterPartnerRequest.PartnerTypeEnum.CONSIGNEE);
        UUID lorry = world.lorry("DL", 20000);
        UUID driver = world.driver("DL");
        UUID trip = world.assignedTrip("DL", customer, consignee, origin, destination, vendor,
                lorry, driver, 5000);
        world.dispatch(trip, origin, 5000, OffsetDateTime.now(ZoneOffset.UTC).minusHours(5));

        // The consignment itself stays ASSIGNED_TO_LOAD for a full truckload; what
        // says it has left is the trip, which dispatch has just moved.
        consignment = jdbc.sql("""
                        SELECT c.id FROM consignment c JOIN sales_order o ON o.id = c.order_id
                         WHERE o.order_no = :no
                        """).param("no", "SO-DL-" + world.salt).query(UUID.class).single();
    }

    // -------------------------------------------------------------- proof

    @When("the desk issues a delivery code")
    public void issue() {
        code = desk().issueDeliveryOtp(consignment, null).getOtp();
        assertThat(code).as("a demo deployment returns the code").matches("\\d{6}");
    }

    private void tryProof(String otp, String recipient, String photo) {
        refusal = null;
        try {
            desk().recordDeliveryProof(consignment, new RecordDeliveryProofRequest()
                    .otp(otp).recipientName(recipient).photoRef(photo), null);
        } catch (FeignException e) {
            refusal = e;
        }
    }

    private String wrong() {
        return "000000".equals(code) ? "111111" : "000000";
    }

    @When("a wrong code is given for the delivery")
    public void wrongOnce() {
        tryProof(wrong(), "Asha Rao", null);
    }

    @When("three wrong codes are given for the delivery")
    public void wrongThrice() {
        for (int i = 0; i < 3; i++) {
            tryProof(wrong(), "Asha Rao", null);
            assertThat(refusal).as("wrong try " + (i + 1)).isNotNull();
        }
    }

    @Then("the delivery is refused as an invalid code")
    public void refusedAsInvalid() {
        assertThat(refusal).as("the proof was refused").isNotNull();
        assertThat(refusal.status()).isEqualTo(409);
        assertThat(refusal.contentUTF8()).contains("otp-invalid");
    }

    @Then("the right code is still refused")
    public void rightButLocked() {
        tryProof(code, "Asha Rao", null);
        refusedAsInvalid();
    }

    @When("the right code is given by recipient {string} with photo {string}")
    public void rightWithPhoto(String recipient, String photo) {
        tryProof(code, recipient, photo);
        assertThat(refusal).as("the right code is accepted").isNull();
    }

    @When("the right code is given by recipient {string} without a photo")
    public void rightWithoutPhoto(String recipient) {
        tryProof(code, recipient, null);
        assertThat(refusal).as("the right code is accepted").isNull();
    }

    @Then("the delivery proof is confirmed for {string} with photo {string}")
    public void confirmedWithPhoto(String recipient, String photo) {
        DeliveryProof proof = desk().getDeliveryProof(consignment);
        assertThat(proof.getStatus()).isEqualTo(DeliveryProof.StatusEnum.CONFIRMED);
        assertThat(proof.getRecipientName()).isEqualTo(recipient);
        assertThat(proof.getPhotoRef()).isEqualTo(photo);
        assertThat(proof.getDeliveredAt()).isNotNull();
    }

    @Then("the delivery proof is confirmed for {string} without a photo")
    public void confirmedWithoutPhoto(String recipient) {
        DeliveryProof proof = desk().getDeliveryProof(consignment);
        assertThat(proof.getStatus()).isEqualTo(DeliveryProof.StatusEnum.CONFIRMED);
        assertThat(proof.getRecipientName()).isEqualTo(recipient);
        assertThat(proof.getPhotoRef()).isNull();
    }

    @Then("a new code can no longer be issued for the delivery")
    public void noMoreCodes() {
        assertThatThrownBy(() -> desk().issueDeliveryOtp(consignment, null))
                .isInstanceOfSatisfying(FeignException.class, e -> {
                    assertThat(e.status()).isEqualTo(409);
                    assertThat(e.contentUTF8()).contains("proof-already-recorded");
                });
    }

    @Given("the delivery has been proved")
    public void proved() {
        issue();
        rightWithPhoto("Asha Rao", "s3://pod/asha.jpg");
    }

    // ------------------------------------------------------------- claims

    private void raise(ClaimType type, String amount) {
        refusal = null;
        try {
            claim = desk().raiseClaim(new RaiseClaimRequest().consignmentId(consignment)
                    .claimType(type).amount(new BigDecimal(amount))
                    .description("Raised over the wire by the end-to-end suite"), null).getId();
        } catch (FeignException e) {
            refusal = e;
        }
    }

    // One keyword only: Given, When and Then match interchangeably, so this serves all three.
    @When("a damage claim of {bigdecimal} is raised for the consignment")
    public void damageClaim(BigDecimal amount) {
        raise(ClaimType.DAMAGE, amount.toPlainString());
    }

    @When("a loss claim of {bigdecimal} is raised for the consignment")
    public void lossClaim(BigDecimal amount) {
        raise(ClaimType.LOSS, amount.toPlainString());
        assertThat(refusal).as("a loss claim needs no proof of delivery").isNull();
    }

    @Then("the claim is refused because delivery has not been proved")
    public void refusedForWantOfProof() {
        assertThat(refusal).as("the claim was refused").isNotNull();
        assertThat(refusal.status()).isEqualTo(409);
        assertThat(refusal.contentUTF8()).contains("claim-needs-proof-of-delivery");
    }

    private void move(ClaimStatus target, BigDecimal approved) {
        desk().transitionClaim(claim, new TransitionClaimRequest().target(target)
                .approvedAmount(approved), null);
    }

    @When("the claim is taken under review")
    public void review() {
        move(ClaimStatus.UNDER_REVIEW, null);
    }

    @When("the claim is approved for {bigdecimal}")
    public void approve(BigDecimal amount) {
        move(ClaimStatus.APPROVED, amount);
    }

    @When("the claim is settled")
    public void settle() {
        move(ClaimStatus.SETTLED, null);
    }

    @Then("the claim is settled for {bigdecimal}")
    public void settledFor(BigDecimal amount) {
        Claim read = desk().getClaim(claim);
        assertThat(read.getStatus()).isEqualTo(ClaimStatus.SETTLED);
        assertThat(read.getApprovedAmount()).isEqualByComparingTo(amount);
        assertThat(read.getClaimNo()).startsWith("CLM-");
    }

    @Then("the consignment's claims list holds exactly that claim")
    public void listHoldsThatClaim() {
        List<Claim> claims = desk().listClaims(null, consignment, null, null).getItems();
        assertThat(claims).extracting(Claim::getId).containsExactly(claim);
    }

    // ------------------------------------------------------- second company

    @Given("a second company with its own administrator is signed in for deliveries")
    public void secondCompany() {
        ContractSupport support = new ContractSupport(port, jdbc, passwordEncoder, transactionManager, jwtEncoder);
        ContractSupport.OtherTenant other = support.provisionTenant(world.salt);
        String token = world.client(null, AuthenticationApi.class).login(new LoginRequest()
                .tenantCode(other.code()).email(other.email()).password(ContractSupport.PASSWORD))
                .getAccessToken();
        secondApi = world.client(token, DeliveryAndClaimsApi.class);
    }

    @Then("the second company is told the consignment does not exist when it asks for a code")
    public void secondNotFound() {
        assertThatThrownBy(() -> secondApi.issueDeliveryOtp(consignment, null))
                .isInstanceOfSatisfying(FeignException.class,
                        e -> assertThat(e.status()).isEqualTo(404));
    }

    @Then("the second company sees none of the consignment's claims")
    public void secondSeesNone() {
        assertThat(secondApi.listClaims(null, consignment, null, null).getItems()).isEmpty();
        assertThatThrownBy(() -> secondApi.getClaim(claim))
                .isInstanceOfSatisfying(FeignException.class,
                        e -> assertThat(e.status()).isEqualTo(404));
    }
}
