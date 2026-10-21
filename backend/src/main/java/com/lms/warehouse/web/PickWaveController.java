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
package com.lms.warehouse.web;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.web.IdResponse;
import com.lms.warehouse.command.WaveCommandService;
import com.lms.warehouse.query.PickWaveView;
import com.lms.warehouse.query.WarehouseQueryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Wave picking: several outbound orders released and picked together. */
@RestController
public class PickWaveController {

    private final WaveCommandService waves;
    private final WarehouseQueryService queries;

    public PickWaveController(WaveCommandService waves, WarehouseQueryService queries) {
        this.waves = waves;
        this.queries = queries;
    }

    @PostMapping("/api/v1/pick-waves")
    public ResponseEntity<IdResponse> release(@Valid @RequestBody ReleaseWaveRequest request) {
        UUID id = waves.release(request.warehouseId(), request.orderIds());
        return ResponseEntity.created(URI.create("/api/v1/pick-waves/" + id))
                .body(new IdResponse(id));
    }

    @GetMapping("/api/v1/pick-waves/{id}")
    public PickWaveView wave(@PathVariable UUID id) {
        return queries.pickWave(id)
                .orElseThrow(() -> new ResourceNotFoundException("Pick wave", id));
    }

    @PostMapping("/api/v1/pick-waves/{id}/pick")
    public ResponseEntity<Void> pick(@PathVariable UUID id) {
        waves.pick(id);
        return ResponseEntity.noContent().build();
    }

    /** @param orderIds served in this sequence when stock runs short */
    public record ReleaseWaveRequest(@NotNull UUID warehouseId,
                                     @NotEmpty List<@NotNull UUID> orderIds) {
    }
}
