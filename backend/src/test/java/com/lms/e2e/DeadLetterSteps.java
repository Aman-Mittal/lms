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

import java.util.List;
import java.util.UUID;

import com.lms.client.api.AuthenticationApi;
import com.lms.client.api.EventDeadLetterApi;
import com.lms.client.model.DiscardParkedEventRequest;
import com.lms.client.model.LoginRequest;
import com.lms.client.model.ParkedEvent;
import com.lms.client.model.ParkedEventReplay;
import com.lms.client.model.ParkedEventStatus;
import com.lms.events.ProbeDriver;
import com.lms.events.ProbeEvent;
import com.lms.events.ProbeListener;
import com.lms.events.command.EventRetrier;
import feign.FeignException;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The event dead-letter over HTTP. The failing event is produced in-process --
 * a probe published through the real registry to a listener told to fail, then
 * one retrier pass, which parks it because the end-to-end context allows a
 * single attempt. Everything after that is the operator's API.
 */
public class DeadLetterSteps {

    @Value("${local.server.port}")
    private int port;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private ApplicationEventPublisher publisher;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private EventRetrier retrier;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JwtEncoder jwtEncoder;

    private HttpWorld world;
    private String key;
    private UUID parked;
    private List<ParkedEvent> listed;
    private ParkedEventReplay replay;
    private EventDeadLetterApi secondApi;

    private EventDeadLetterApi events() {
        return world.api(EventDeadLetterApi.class);
    }

    @Given("the demo administrator is signed in to look after events")
    public void signIn() {
        world = new HttpWorld(port);
        world.signInAsDemoAdministrator();
    }

    @Given("an event for the demo company has failed until it was parked")
    public void failedUntilParked() {
        UUID demo = jdbc.sql("SELECT tenant_id FROM org_unit WHERE id = :unit")
                .param("unit", world.orgUnitId()).query(UUID.class).single();
        ProbeDriver probes = new ProbeDriver(publisher, transactionManager, jdbc, retrier);
        key = "e2e-" + world.salt;
        probes.publish(demo, key, ProbeListener.ALWAYS);
        probes.step();
        parked = probes.parkedId(key);
        assertThat(parked).as("the event was parked").isNotNull();
    }

    @When("the administrator lists the parked events")
    public void list() {
        listed = events().listParkedEvents(ParkedEventStatus.PARKED, ProbeEvent.class.getName(), null, null, 200)
                .getItems();
    }

    @Then("the parked event is listed with the failure that parked it")
    public void listedWithFailure() {
        ParkedEvent event = listed.stream().filter(e -> parked.equals(e.getId())).findFirst().orElseThrow();
        assertThat(event.getStatus()).isEqualTo(ParkedEventStatus.PARKED);
        assertThat(event.getListenerId()).contains("ProbeListener");
        assertThat(event.getAttempts()).isEqualTo(1);
        assertThat(event.getLastError()).contains("was told to fail");
        assertThat(events().getParkedEvent(parked).getPayload()).contains(key);
    }

    @When("the fault is fixed and the administrator replays the event")
    public void fixAndReplay() {
        ProbeListener.failTimes(key, 0);
        replayIt();
    }

    @When("the administrator replays the event")
    public void replayIt() {
        replay = events().replayParkedEvent(parked, null);
    }

    @Then("the replay reports the event as replayed")
    public void replayed() {
        assertThat(replay.getOutcome()).isEqualTo(ParkedEventReplay.OutcomeEnum.REPLAYED);
        assertThat(ProbeListener.successes(key)).as("the listener completed").isEqualTo(1);
    }

    @Then("the event is no longer in the parked list")
    public void goneFromList() {
        list();
        assertThat(listed).extracting(ParkedEvent::getId).doesNotContain(parked);
        assertThat(events().getParkedEvent(parked).getSummary().getStatus()).isEqualTo(ParkedEventStatus.REPLAYED);
    }

    @Then("the replay reports the event as parked again after {int} attempts")
    public void parkedAgain(int attempts) {
        assertThat(replay.getOutcome()).isEqualTo(ParkedEventReplay.OutcomeEnum.PARKED_AGAIN);
        assertThat(replay.getAttempts()).isEqualTo(attempts);
        assertThat(events().getParkedEvent(parked).getSummary().getStatus()).isEqualTo(ParkedEventStatus.PARKED);
    }

    @When("the administrator discards the event because {string}")
    public void discard(String reason) {
        events().discardParkedEvent(parked, new DiscardParkedEventRequest().reason(reason), null);
    }

    @Then("the event is listed as discarded with the reason {string}")
    public void discarded(String reason) {
        List<ParkedEvent> discarded = events().listParkedEvents(ParkedEventStatus.DISCARDED,
                ProbeEvent.class.getName(), null, null, 200).getItems();
        ParkedEvent event = discarded.stream().filter(e -> parked.equals(e.getId())).findFirst().orElseThrow();
        assertThat(event.getResolutionNote()).isEqualTo(reason);
    }

    @Given("a second company with its own administrator is signed in to look after events")
    public void secondCompany() {
        ContractSupport support = new ContractSupport(port, jdbc, passwordEncoder, transactionManager, jwtEncoder);
        ContractSupport.OtherTenant other = support.provisionTenant(world.salt);
        String token = world.client(null, AuthenticationApi.class).login(new LoginRequest()
                .tenantCode(other.code()).email(other.email()).password(ContractSupport.PASSWORD))
                .getAccessToken();
        secondApi = world.client(token, EventDeadLetterApi.class);
    }

    @Then("the second company is told the parked event does not exist")
    public void secondNotFound() {
        assertThatThrownBy(() -> secondApi.getParkedEvent(parked))
                .isInstanceOfSatisfying(FeignException.class, e -> {
                    assertThat(e.status()).isEqualTo(404);
                    assertThat(e.contentUTF8()).contains("not-found");
                });
        assertThatThrownBy(() -> secondApi.replayParkedEvent(parked, null))
                .isInstanceOfSatisfying(FeignException.class, e -> assertThat(e.status()).isEqualTo(404));
        assertThat(secondApi.listParkedEvents(null, null, null, null, 200).getItems())
                .extracting(ParkedEvent::getId).doesNotContain(parked);
    }
}
