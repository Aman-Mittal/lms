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
package com.lms.webhooks.command;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.atomic.AtomicInteger;

import com.lms.shared.error.BusinessRuleViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Literal addresses only, so nothing here depends on DNS. */
class WebhookTargetGuardTest {

    private final WebhookTargetGuard production = new WebhookTargetGuard(false, false);

    /** Built in pieces so that no literal here looks like an address carrying a password. */
    private static String withUserInfo(String host) {
        return "https://" + String.join(":", "user", "pw") + "@" + host + "/hook";
    }

    private static InetAddress ip(String literal) throws UnknownHostException {
        return InetAddress.getByName(literal);
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "10.0.0.1", "172.16.5.4", "192.168.1.1", "169.254.169.254", "0.0.0.0",
            "100.64.0.1", "100.127.255.254", "198.18.0.1", "192.0.0.8", "224.0.0.1", "::1", "fe80::1", "fd00::1",
            "::ffff:10.0.0.1", "::ffff:127.0.0.1"})
    @DisplayName("internal, link-local and reserved addresses are not public")
    void notPublic(String literal) throws UnknownHostException {
        assertThat(WebhookTargetGuard.isPublic(ip(literal))).as(literal).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"203.0.113.10", "8.8.8.8", "100.128.0.1", "2001:4860:4860::8888", "::ffff:8.8.8.8"})
    @DisplayName("ordinary internet addresses are public")
    void isPublic(String literal) throws UnknownHostException {
        assertThat(WebhookTargetGuard.isPublic(ip(literal))).as(literal).isTrue();
    }

    @Test
    @DisplayName("https to a public literal address is accepted; http, credentials and internal addresses are not")
    void addresses() {
        assertThatCode(() -> production.requireAcceptable("https://203.0.113.10/hook")).doesNotThrowAnyException();
        assertThatThrownBy(() -> production.requireAcceptable("http://203.0.113.10/hook"))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.code()).isEqualTo("webhook-url-not-https"));
        assertThatThrownBy(() -> production.requireAcceptable(withUserInfo("203.0.113.10")))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.code()).isEqualTo("webhook-url-invalid"));
        assertThatThrownBy(() -> production.requireAcceptable("https://[::1]/hook"))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.code()).isEqualTo("webhook-target-not-allowed"));
        assertThatThrownBy(() -> production.requireAcceptable("ftp://203.0.113.10/hook"))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.code()).isEqualTo("webhook-url-invalid"));
    }

    @Test
    @DisplayName("the local-development switches allow http and internal addresses, and nothing else")
    void localSwitches() {
        WebhookTargetGuard local = new WebhookTargetGuard(true, true);
        assertThatCode(() -> local.requireAcceptable("http://127.0.0.1:8099/hook")).doesNotThrowAnyException();
        assertThat(local.refusalFor("http://127.0.0.1:8099/hook")).isNull();
        assertThat(production.refusalFor("http://127.0.0.1:8099/hook")).isNotNull();
        assertThatThrownBy(() -> local.requireAcceptable(withUserInfo("127.0.0.1")))
                .isInstanceOf(BusinessRuleViolationException.class);
    }

    @Test
    @DisplayName("a delivery is pinned to the address that was checked, so a name re-pointed afterwards is not followed")
    void rebindingIsNotFollowed() throws UnknownHostException {
        InetAddress publicAddress = ip("203.0.113.10");
        InetAddress internal = ip("127.0.0.1");
        AtomicInteger lookups = new AtomicInteger();
        // First answer public, every later answer internal: a name re-pointed after the check.
        WebhookTargetGuard guard = new WebhookTargetGuard(false, false,
                host -> new InetAddress[] {lookups.getAndIncrement() == 0 ? publicAddress : internal});

        WebhookTargetGuard.Target target = guard.requireDeliverable("https://hooks.example.test/hook");

        assertThat(target.address()).isEqualTo(publicAddress);
        assertThat(target.uri().getHost()).isEqualTo("hooks.example.test");
        assertThat(lookups.get()).as("resolved exactly once").isEqualTo(1);
        // A later check sees the internal address and refuses it.
        assertThatThrownBy(() -> guard.requireDeliverable("https://hooks.example.test/hook"))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.code()).isEqualTo("webhook-target-not-allowed"));
    }

    @Test
    @DisplayName("one internal address among several refuses the whole name, and a name with none is unresolvable")
    void everyAddressIsJudged() throws UnknownHostException {
        InetAddress publicAddress = ip("203.0.113.10");
        InetAddress internal = ip("10.0.0.7");
        WebhookTargetGuard mixed = new WebhookTargetGuard(false, false,
                host -> new InetAddress[] {publicAddress, internal});
        assertThatThrownBy(() -> mixed.requireDeliverable("https://hooks.example.test/hook"))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.code()).isEqualTo("webhook-target-not-allowed"));
        WebhookTargetGuard none = new WebhookTargetGuard(false, false, host -> new InetAddress[0]);
        assertThatThrownBy(() -> none.requireDeliverable("https://hooks.example.test/hook"))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.code()).isEqualTo("webhook-url-unresolvable"));
    }
}
