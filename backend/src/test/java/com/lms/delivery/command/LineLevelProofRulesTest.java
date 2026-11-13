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
package com.lms.delivery.command;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.lms.delivery.command.domain.DeliveryProof;
import com.lms.delivery.command.domain.GoodsCondition;
import com.lms.delivery.command.domain.ProofStatus;
import com.lms.settings.api.ScopeType;
import com.lms.settings.api.SettingDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The states of a line-level proof, and the settings that govern its submission. */
class LineLevelProofRulesTest {

    private static DeliveryProof draft() {
        return DeliveryProof.draft(UUID.randomUUID(), UUID.randomUUID(), Instant.now());
    }

    @Test
    @DisplayName("a draft is pending, open, with no code, and proves nothing")
    void draftIsOpen() {
        DeliveryProof draft = draft();
        assertThat(draft.status()).isEqualTo(ProofStatus.PENDING);
        assertThat(draft.open()).isTrue();
        assertThat(draft.otpHash()).isNull();
        assertThat(draft.paper()).isFalse();
        assertThat(ProofStatus.PENDING.proves()).isFalse();
        assertThat(ProofStatus.NOT_RECEIVED.proves()).isFalse();
    }

    @Test
    @DisplayName("confirmed and submitted prove the delivery; nothing else does")
    void whatProves() {
        assertThat(List.of(ProofStatus.values())).filteredOn(ProofStatus::proves)
                .containsExactly(ProofStatus.CONFIRMED, ProofStatus.SUBMITTED);
    }

    @Test
    @DisplayName("submitting by paper records who keyed it, and drops the remark of clean goods")
    void paperSubmission() {
        UUID keyer = UUID.randomUUID();
        DeliveryProof submitted = draft().submitted("R", Instant.now(), null, null, GoodsCondition.CLEAN,
                "ignored", true, keyer);
        assertThat(submitted.status()).isEqualTo(ProofStatus.SUBMITTED);
        assertThat(submitted.paper()).isTrue();
        assertThat(submitted.keyedBy()).isEqualTo(keyer);
        assertThat(submitted.remark()).isNull();
        assertThat(submitted.open()).isFalse();

        DeliveryProof damaged = draft().submitted("R", Instant.now(), BigDecimal.ONE, BigDecimal.ONE,
                GoodsCondition.DAMAGE, "Crushed", false, null);
        assertThat(damaged.remark()).isEqualTo("Crushed");
        assertThat(damaged.clean()).isFalse();
    }

    @Test
    @DisplayName("a submitted proof is reversed and then open again; a draft cannot be reversed")
    void reversal() {
        DeliveryProof submitted = draft().submitted("R", Instant.now(), null, null, null, null, true, null);
        DeliveryProof reversed = submitted.reversed();
        assertThat(reversed.status()).isEqualTo(ProofStatus.REVERSED);
        assertThat(reversed.open()).isTrue();
        assertThatThrownBy(() -> draft().reversed()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("a fresh code on a draft or a refused proof moves it to OTP_ISSUED; a proof stays as it is")
    void newCode() {
        Instant now = Instant.now();
        assertThat(draft().withNewCode("h", now, now.plusSeconds(60)).status()).isEqualTo(ProofStatus.OTP_ISSUED);
        assertThat(draft().notReceived("Gate closed").withNewCode("h", now, now.plusSeconds(60)).status())
                .isEqualTo(ProofStatus.OTP_ISSUED);
        DeliveryProof submitted = draft().submitted("R", now, null, null, null, null, true, null);
        assertThat(submitted.withNewCode("h", now, now.plusSeconds(60)).status()).isEqualTo(ProofStatus.SUBMITTED);
    }

    @Test
    @DisplayName("the POD settings default to lenient and may be set per unit and customer")
    void settings() {
        List<SettingDefinition> definitions = new DeliverySettingDefinitions().definitions();
        assertThat(definitions).extracting(SettingDefinition::key).containsExactly(
                "delivery.pod.quantityTolerancePercent", "delivery.pod.feedingRequired",
                "delivery.pod.frontDocumentRequired", "delivery.pod.backDocumentRequired");
        assertThat(definitions).extracting(SettingDefinition::defaultValue)
                .containsExactly(new BigDecimal("100"), false, false, false);
        assertThat(definitions).allSatisfy(d -> assertThat(d.scopes()).containsExactly(ScopeType.ORG_DEFAULT,
                ScopeType.ORG_UNIT, ScopeType.CUSTOMER, ScopeType.COMBINATION));
    }
}
