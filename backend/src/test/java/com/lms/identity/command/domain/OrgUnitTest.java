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
package com.lms.identity.command.domain;

import java.util.UUID;

import com.lms.identity.command.domain.OrgUnit.UnitType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OrgUnitTest {

    @Test
    @DisplayName("levels run HQ, LOB, COUNTRY, ZONE (REGION alike), BRANCH, HUB; only zone and below carry a state")
    void levels() {
        assertThat(UnitType.HQ.rank()).isZero();
        assertThat(UnitType.LOB.rank()).isEqualTo(1);
        assertThat(UnitType.COUNTRY.rank()).isEqualTo(2);
        assertThat(UnitType.ZONE.rank()).isEqualTo(3).isEqualTo(UnitType.REGION.rank());
        assertThat(UnitType.BRANCH.rank()).isEqualTo(4);
        assertThat(UnitType.HUB.rank()).isEqualTo(5);
        assertThat(UnitType.COUNTRY.carriesState()).isFalse();
        assertThat(UnitType.ZONE.carriesState()).isTrue();
        assertThat(UnitType.HUB.carriesState()).isTrue();
    }

    @Test
    @DisplayName("a child's path is its parent's and its own slug; a revision keeps slug and path")
    void childAndRevision() {
        OrgUnit root = new OrgUnit(UUID.randomUUID(), UUID.randomUUID(), null, "t", "/t/", "Head Office",
                UnitType.HQ, null, null, 3L, null, null);
        OrgUnit india = OrgUnit.childOf(root, "in", "India", UnitType.COUNTRY, "IN", null);
        assertThat(india.path()).isEqualTo("/t/in/");
        assertThat(india.parentId()).isEqualTo(root.id());
        assertThat(india.version()).isNull();
        OrgUnit renamed = india.revised(4L, "Bharat", UnitType.COUNTRY, "IN", null);
        assertThat(renamed.path()).isEqualTo("/t/in/");
        assertThat(renamed.slug()).isEqualTo("in");
        assertThat(renamed.version()).isEqualTo(4L);
        assertThat(OrgUnit.safeSlug("north-1")).isTrue();
        assertThat(OrgUnit.safeSlug("North")).isFalse();
        assertThat(OrgUnit.safeSlug("a%")).isFalse();
    }
}
