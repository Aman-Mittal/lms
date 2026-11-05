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

import java.util.UUID;

import com.lms.client.api.NumberSeriesApi;
import com.lms.client.model.CreateNumberSeriesRequest;
import com.lms.client.model.NumberFormatPreview;
import com.lms.client.model.NumberRelease;
import com.lms.client.model.NumberVerification;
import com.lms.client.model.NumberYearCode;
import com.lms.client.model.NumberedDocumentType;
import com.lms.client.model.ReleaseNumberRequest;
import feign.FeignException;
import io.cucumber.java.After;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.beans.factory.annotation.Value;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Number series over HTTP, in the shared demo tenant. The series this
 * scenario sets up is tenant-wide, so it is switched off in an {@code @After}
 * hook whatever the outcome: left active, it would refuse the next run's
 * series for the same type and scope.
 */
public class NumberSeriesFlowSteps {

    @Value("${local.server.port}")
    private int port;

    private HttpWorld world;
    private UUID seriesId;
    private NumberFormatPreview preview;
    private String first;
    private String second;

    @After("@number-series-e2e")
    public void switchOff() {
        if (seriesId != null && world != null) {
            try {
                api().deactivateNumberSeries(seriesId, null);
            } catch (FeignException e) {
                // Already gone or already off.
            }
        }
    }

    @Given("the demo administrator is signed in to manage number series")
    public void signIn() {
        world = new HttpWorld(port);
        world.signInAsDemoAdministrator();
        // A previous run that died before its hook may have left a series active.
        api().listNumberSeries(NumberedDocumentType.GATE_PASS, null, 200).getItems().stream()
                .filter(s -> s.getActive() && s.getOrgUnitId() == null)
                .forEach(s -> api().deactivateNumberSeries(s.getId(), null));
    }

    @When("the administrator previews a gate-pass format with a two-year code")
    public void previewFormat() {
        preview = api().previewNumberFormat(prefix(), NumberYearCode.SPAN, "/", 4, 4, "UTC");
    }

    @Then("the preview shows this financial year and the next")
    public void previewShowsYears() {
        int year = preview.getFinancialYear();
        assertThat(preview.getSample()).isEqualTo("%s/%02d%02d/0001".formatted(prefix(), year % 100, (year + 1) % 100));
        assertThat(preview.getNextYearSample())
                .isEqualTo("%s/%02d%02d/0001".formatted(prefix(), (year + 1) % 100, (year + 2) % 100));
    }

    @When("the administrator sets up a tenant-wide gate-pass series")
    public void setUp() {
        seriesId = api().createNumberSeries(new CreateNumberSeriesRequest().code("e2e-gp-" + world.salt)
                .documentType(NumberedDocumentType.GATE_PASS).prefix(prefix()).yearCode(NumberYearCode.SPAN)
                .separator("/").counterLength(4).description("End-to-end gate passes"), null).getId();
    }

    @When("two gate passes are numbered from it")
    public void issueTwo() {
        first = api().issueNumber(seriesId, null).getNumber();
        second = api().issueNumber(seriesId, null).getNumber();
    }

    @Then("the two numbers are consecutive and the preview offers the third")
    public void consecutive() {
        assertThat(first).isEqualTo(preview.getSample());
        assertThat(second).isEqualTo(preview.getSample().replace("/0001", "/0002"));
        assertThat(api().previewNextNumber(seriesId).getNumber())
                .isEqualTo(preview.getSample().replace("/0001", "/0003"));
        assertThat(api().getNumberSeries(seriesId).getLastIssued()).isEqualTo(second);
    }

    @When("the first gate-pass number is given back because the pass was torn")
    public void giveBack() {
        api().releaseNumber(seriesId, new ReleaseNumberRequest().number(first).reason("pass torn"), null);
    }

    @Then("the first number verifies as released and the second as issued")
    public void verify() {
        NumberVerification released = api().verifyDocumentNumber(NumberedDocumentType.GATE_PASS, first);
        assertThat(released.getBelongs()).isTrue();
        assertThat(released.getSeriesId()).isEqualTo(seriesId);
        assertThat(released.getStatus()).isEqualTo(NumberVerification.StatusEnum.RELEASED);
        assertThat(api().verifyDocumentNumber(NumberedDocumentType.GATE_PASS, second).getStatus())
                .isEqualTo(NumberVerification.StatusEnum.ISSUED);
    }

    @Then("the release is listed with its reason")
    public void listed() {
        assertThat(api().listNumberReleases(seriesId, null, null).getItems())
                .extracting(NumberRelease::getNumber, NumberRelease::getReason)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(first, "pass torn"));
    }

    @Then("giving the first number back again is refused with {string}")
    public void giveBackAgain(String code) {
        assertRefused(() -> api().releaseNumber(seriesId,
                new ReleaseNumberRequest().number(first).reason("again"), null), 409, code);
    }

    @Then("removing the series is refused with {string}")
    public void removeRefused(String code) {
        assertRefused(() -> api().deleteNumberSeries(seriesId), 409, code);
    }

    @When("the series is switched off")
    public void switchOffNow() {
        api().deactivateNumberSeries(seriesId, null);
        assertThat(api().getNumberSeries(seriesId).getActive()).isFalse();
    }

    @Then("numbering from it is refused with {string}")
    public void issueRefused(String code) {
        assertRefused(() -> api().issueNumber(seriesId, null), 409, code);
    }

    @Then("a series that does not exist is not found")
    public void notFound() {
        assertRefused(() -> api().getNumberSeries(UUID.randomUUID()), 404, "not-found");
    }

    private NumberSeriesApi api() {
        return world.api(NumberSeriesApi.class);
    }

    private String prefix() {
        return "E" + world.salt;
    }

    private static void assertRefused(Runnable call, int status, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(FeignException.class, e -> {
            assertThat(e.status()).isEqualTo(status);
            assertThat(e.contentUTF8()).contains("\"" + code + "\"");
        });
    }
}
