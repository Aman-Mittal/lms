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
package com.lms.telematics.command;

import java.util.List;
import java.util.UUID;

import com.lms.telematics.command.domain.GeofenceEvent;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface GeofenceEventRepository extends CrudRepository<GeofenceEvent, UUID> {

    /** Served by {@code geofence_event_trip_idx}, oldest first. */
    @Query("""
            SELECT * FROM geofence_event
             WHERE tenant_id = :tenantId AND trip_id = :tripId
             ORDER BY occurred_at
            """)
    List<GeofenceEvent> findByTrip(@Param("tenantId") UUID tenantId, @Param("tripId") UUID tripId);
}
