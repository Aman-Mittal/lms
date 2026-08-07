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
package com.lms.masterdata.api;

import java.util.UUID;

/**
 * Whether a partner may be given work.
 *
 * <p>Vision document 3.2.1 puts this plainly: no work goes to a partner that is
 * not ACTIVE. Offering a load to a blacklisted vendor is exactly that, so
 * sourcing asks before every offer rather than trusting a status copied at the
 * time the routing guide was written -- a guide is a standing arrangement and
 * outlives the standing of the vendors in it.
 */
public interface PartnerStandingPort {

    /**
     * True when the partner exists and is in a status that permits business.
     *
     * <p>Returns false for an unknown partner rather than throwing. The caller
     * is asking "may I use this one", and "there is no such partner" is a
     * perfectly good no.
     */
    boolean canTransact(UUID partnerId);

    /** The partner's code, for messages that a human has to act on. */
    String codeOf(UUID partnerId);
}
