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
package com.lms.tracking.web;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.lms.tracking.command.TrackingLinkService;
import com.lms.tracking.query.TrackingLinkQueryService;
import com.lms.tracking.query.TrackingLinkView;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Issuing, listing and revoking tracking links. Authorisation is in the services. */
@RestController
@RequestMapping("/api/v1")
public class TrackingLinkController {

    private final TrackingLinkService links;
    private final TrackingLinkQueryService queries;

    public TrackingLinkController(TrackingLinkService links, TrackingLinkQueryService queries) {
        this.links = links;
        this.queries = queries;
    }

    @PostMapping("/consignments/{consignmentId}/tracking-links")
    public ResponseEntity<IssuedLink> issue(@PathVariable UUID consignmentId,
                                            @Valid @RequestBody(required = false) IssueRequest request) {
        TrackingLinkService.Issued issued = links.issue(consignmentId,
                request == null ? null : request.expiresInDays());
        return ResponseEntity.created(URI.create("/api/v1/consignments/" + consignmentId + "/tracking-links"))
                .header("Cache-Control", "no-store")
                .body(new IssuedLink(issued.id(), issued.token(), issued.expiresAt()));
    }

    @GetMapping("/consignments/{consignmentId}/tracking-links")
    public LinkList list(@PathVariable UUID consignmentId) {
        return new LinkList(queries.linksOf(consignmentId));
    }

    @PostMapping("/tracking-links/{id}/revocations")
    public ResponseEntity<Void> revoke(@PathVariable UUID id) {
        links.revoke(id);
        return ResponseEntity.noContent().build();
    }

    public record IssueRequest(Integer expiresInDays) {
    }

    /** The token appears here and nowhere else, ever again. */
    public record IssuedLink(UUID id, String token, Instant expiresAt) {
    }

    public record LinkList(List<TrackingLinkView> items) {
    }
}
