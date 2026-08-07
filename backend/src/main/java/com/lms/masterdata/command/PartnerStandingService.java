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
package com.lms.masterdata.command;

import java.util.UUID;

import com.lms.masterdata.api.PartnerStandingPort;
import com.lms.masterdata.command.domain.BusinessPartner;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Serves {@link PartnerStandingPort} from the partner register. */
@Service
@Transactional(readOnly = true)
public class PartnerStandingService implements PartnerStandingPort {

    private final BusinessPartnerRepository partners;

    public PartnerStandingService(BusinessPartnerRepository partners) {
        this.partners = partners;
    }

    @Override
    public boolean canTransact(UUID partnerId) {
        if (partnerId == null) {
            return false;
        }
        return partners.findById(partnerId)
                .map(BusinessPartner::canTransact)
                .orElse(false);
    }

    @Override
    public String codeOf(UUID partnerId) {
        return partners.findById(partnerId)
                .map(BusinessPartner::code)
                // The identifier is a poor thing to show a human, but it beats
                // failing the caller's real work to complain about a name.
                .orElseGet(() -> String.valueOf(partnerId));
    }
}
