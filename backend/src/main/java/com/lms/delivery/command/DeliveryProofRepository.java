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

import java.util.Optional;
import java.util.UUID;

import com.lms.delivery.command.domain.DeliveryProof;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface DeliveryProofRepository extends CrudRepository<DeliveryProof, UUID> {

    /** Served by the unique index {@code delivery_proof_consignment_idx}. */
    @Query("SELECT * FROM delivery_proof WHERE tenant_id = :tenantId AND consignment_id = :consignmentId")
    Optional<DeliveryProof> forConsignment(@Param("tenantId") UUID tenantId,
                                           @Param("consignmentId") UUID consignmentId);
}
