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
package com.lms.webhooks.web;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.query.Slice;
import com.lms.webhooks.command.WebhookSubscriptionService;
import com.lms.webhooks.query.WebhookDeliveryView;
import com.lms.webhooks.query.WebhookQueryService;
import com.lms.webhooks.query.WebhookSubscriptionView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Webhook subscriptions and their deliveries. Authorisation is in the services. */
@RestController
@RequestMapping("/api/v1/webhooks")
public class WebhookController {

    private final WebhookSubscriptionService subscriptions;
    private final WebhookQueryService queries;

    public WebhookController(WebhookSubscriptionService subscriptions, WebhookQueryService queries) {
        this.subscriptions = subscriptions;
        this.queries = queries;
    }

    @PostMapping("/subscriptions")
    public ResponseEntity<SecretIssued> subscribe(@Valid @RequestBody SubscriptionRequest request) {
        WebhookSubscriptionService.Created created =
                subscriptions.subscribe(request.name(), request.url(), request.eventTypes());
        return ResponseEntity.created(URI.create("/api/v1/webhooks/subscriptions/" + created.id()))
                .header("Cache-Control", "no-store")
                .body(new SecretIssued(created.id(), created.secret()));
    }

    @GetMapping("/subscriptions")
    public Slice<WebhookSubscriptionView> list(@RequestParam(required = false) String cursor,
                                               @RequestParam(required = false) Integer limit) {
        return queries.subscriptions(cursor, limit);
    }

    @GetMapping("/subscriptions/{id}")
    public WebhookSubscriptionView get(@PathVariable UUID id) {
        return queries.subscription(id)
                .orElseThrow(() -> new ResourceNotFoundException("Webhook subscription", id));
    }

    @PutMapping("/subscriptions/{id}")
    public ResponseEntity<Void> revise(@PathVariable UUID id, @Valid @RequestBody RevisionRequest request) {
        subscriptions.revise(id, request.name(), request.url(), request.eventTypes(), request.active());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/subscriptions/{id}/secret-rotations")
    public ResponseEntity<SecretIssued> rotate(@PathVariable UUID id) {
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(new SecretIssued(id, subscriptions.rotateSecret(id)));
    }

    @PostMapping("/subscriptions/{id}/pings")
    public ResponseEntity<DeliveryQueued> ping(@PathVariable UUID id) {
        return ResponseEntity.accepted().body(new DeliveryQueued(subscriptions.ping(id)));
    }

    @GetMapping("/subscriptions/{id}/deliveries")
    public Slice<WebhookDeliveryView> deliveries(@PathVariable UUID id,
                                                 @RequestParam(required = false) String status,
                                                 @RequestParam(required = false) String cursor,
                                                 @RequestParam(required = false) Integer limit) {
        if (queries.subscription(id).isEmpty()) {
            throw new ResourceNotFoundException("Webhook subscription", id);
        }
        return queries.deliveries(id, status, cursor, limit);
    }

    @PostMapping("/deliveries/{id}/redeliveries")
    public ResponseEntity<Void> redeliver(@PathVariable UUID id) {
        subscriptions.redeliver(id);
        return ResponseEntity.accepted().build();
    }

    public record SubscriptionRequest(@NotBlank String name, @NotBlank String url,
                                      @NotEmpty List<String> eventTypes) {
    }

    public record RevisionRequest(@NotBlank String name, @NotBlank String url,
                                  @NotEmpty List<String> eventTypes, @NotNull Boolean active) {
    }

    /** The secret appears here, and in a rotation, and nowhere else. */
    public record SecretIssued(UUID id, String secret) {
    }

    public record DeliveryQueued(UUID deliveryId) {
    }
}
