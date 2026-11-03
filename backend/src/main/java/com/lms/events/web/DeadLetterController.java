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
package com.lms.events.web;

import java.util.UUID;

import com.lms.events.command.DeadLetterService;
import com.lms.events.query.DeadLetterDetail;
import com.lms.events.query.DeadLetterQueryService;
import com.lms.events.query.DeadLetterView;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.query.Slice;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The parked list and an operator's two actions on it. Authorisation is in the services. */
@RestController
@RequestMapping("/api/v1/event-dead-letters")
public class DeadLetterController {

    private final DeadLetterService deadLetters;
    private final DeadLetterQueryService queries;

    public DeadLetterController(DeadLetterService deadLetters, DeadLetterQueryService queries) {
        this.deadLetters = deadLetters;
        this.queries = queries;
    }

    @GetMapping
    public Slice<DeadLetterView> list(@RequestParam(required = false) String status,
                                      @RequestParam(required = false) String eventType,
                                      @RequestParam(required = false) String listenerId,
                                      @RequestParam(required = false) String cursor,
                                      @RequestParam(required = false) Integer limit) {
        return queries.list(status, eventType, listenerId, cursor, limit);
    }

    @GetMapping("/{id}")
    public DeadLetterDetail get(@PathVariable UUID id) {
        return queries.find(id).orElseThrow(() -> new ResourceNotFoundException("Parked event", id));
    }

    @PostMapping("/{id}/replays")
    public DeadLetterService.Replay replay(@PathVariable UUID id) {
        return deadLetters.replay(id);
    }

    @PostMapping("/{id}/discards")
    public ResponseEntity<Void> discard(@PathVariable UUID id, @RequestBody DiscardRequest request) {
        deadLetters.discard(id, request.reason());
        return ResponseEntity.noContent().build();
    }

    public record DiscardRequest(String reason) {
    }
}
