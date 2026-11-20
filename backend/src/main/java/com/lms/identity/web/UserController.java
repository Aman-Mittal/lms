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
 */package com.lms.identity.web;

import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.UUID;

import com.lms.identity.access.SubjectAttributes;
import com.lms.identity.command.AccessPolicyCommandService;
import com.lms.identity.command.UserAdminService;
import com.lms.identity.query.AccessQueryService;
import com.lms.identity.query.UserAccessAttributesView;
import com.lms.identity.query.UserQueryService;
import com.lms.identity.query.UserView;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.web.IdResponse;
import com.lms.shared.web.IfMatch;
import com.lms.shared.web.ReasonRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Users visible to the caller (vision document 3.1), and their administration.
 *
 * <p>"Visible" is the org hierarchy doing its work: the listing is bounded by
 * the caller's own organisational path, so a branch manager sees their branch
 * and everything under it and nothing above. The only parameters narrow that
 * listing (a search text, a status); none widens it. Creating, editing,
 * switching off and setting passwords need {@code USER_MANAGE}.
 */
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserQueryService users;
    private final AccessQueryService access;
    private final AccessPolicyCommandService accessCommands;

    private final UserAdminService admin;

    public UserController(UserQueryService users, AccessQueryService access,
                          AccessPolicyCommandService accessCommands, UserAdminService admin) {
        this.admin = admin;
        this.users = users;
        this.access = access;
        this.accessCommands = accessCommands;
    }

    @GetMapping
    public List<UserView> list(@RequestParam(required = false) String q,
                               @RequestParam(required = false) String status) {
        return users.findVisible(q, status);
    }

    @GetMapping("/{id}")
    public ResponseEntity<UserView> get(@PathVariable UUID id) {
        UserView view = users.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("AppUser", id));
        return ResponseEntity.ok().eTag(IfMatch.etag(view.version())).body(view);
    }

    /** Creates a user with the login id and password the administrator typed. */
    @PostMapping
    public ResponseEntity<IdResponse> create(@Valid @RequestBody CreateUserRequest request) {
        UUID id = admin.create(request.orgUnitId(), request.email(), request.password(),
                request.fullName(), request.roles());
        return ResponseEntity.created(URI.create("/api/v1/users/" + id)).body(new IdResponse(id));
    }

    /** Edits the name and roles; a stale {@code If-Match} is a 409. */
    @PutMapping("/{id}")
    public ResponseEntity<Void> update(@PathVariable UUID id,
                                       @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                       @Valid @RequestBody UpdateUserRequest request) {
        admin.update(id, IfMatch.version(ifMatch, "the user's version"), request.fullName(), request.roles());
        return ResponseEntity.noContent().build();
    }

    /** Switches the user off, with a reason. There is no delete. */
    @PostMapping("/{id}/deactivate")
    public ResponseEntity<Void> deactivate(@PathVariable UUID id,
                                           @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                           @Valid @RequestBody ReasonRequest request) {
        admin.deactivate(id, IfMatch.optionalVersion(ifMatch, "the user's version"), request.reason());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/reactivate")
    public ResponseEntity<Void> reactivate(@PathVariable UUID id,
                                           @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                           @Valid @RequestBody ReasonRequest request) {
        admin.reactivate(id, IfMatch.optionalVersion(ifMatch, "the user's version"), request.reason());
        return ResponseEntity.noContent().build();
    }

    /** Sets a new password for the user and signs them out everywhere. */
    @PutMapping("/{id}/password")
    public ResponseEntity<Void> setPassword(@PathVariable UUID id,
                                            @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                            @Valid @RequestBody SetPasswordRequest request) {
        admin.setPassword(id, IfMatch.optionalVersion(ifMatch, "the user's version"), request.password());
        return ResponseEntity.noContent().build();
    }

    /**
     * What one user is permitted to do.
     *
     * <p>Separate from the user document because it is a different question
     * asked at a different time -- an administrator auditing access, not a
     * screen rendering a name -- and joining it in would make every listing pay
     * for a permission expansion nobody asked for.
     */
    @GetMapping("/{id}/permissions")
    public List<String> permissions(@PathVariable UUID id) {
        return users.findPermissions(id);
    }

    /** The attributes the user's next access token will carry. */
    @GetMapping("/{id}/access-attributes")
    public UserAccessAttributesView accessAttributes(@PathVariable UUID id) {
        return access.attributesOf(id)
                .orElseThrow(() -> new ResourceNotFoundException("AppUser", id));
    }

    /**
     * Replaces the user's access attributes. A field left out is cleared,
     * which means "unrestricted" -- this is a PUT of the whole set, so a
     * forgotten field cannot quietly keep an old limit alive.
     */
    @PutMapping("/{id}/access-attributes")
    public ResponseEntity<Void> setAccessAttributes(@PathVariable UUID id,
                                                    @RequestBody AccessAttributesRequest request) {
        accessCommands.setUserAttributes(id, new SubjectAttributes(request.region(),
                request.freightModes(), request.partnerId(), request.approvalLimit()));
        return ResponseEntity.noContent().build();
    }

    public record CreateUserRequest(
            UUID orgUnitId,
            @NotBlank @Email String email,
            @NotBlank String password,
            @NotBlank String fullName,
            List<String> roles) {

        /** Keeps the password out of anything that prints the request. */
        @Override
        public String toString() {
            return "CreateUserRequest[email=" + email + "]";
        }
    }

    public record UpdateUserRequest(@NotBlank String fullName, List<String> roles) {
    }

    public record SetPasswordRequest(@NotBlank String password) {

        @Override
        public String toString() {
            return "SetPasswordRequest[]";
        }
    }

    public record AccessAttributesRequest(String region, List<String> freightModes,
                                          UUID partnerId, BigDecimal approvalLimit) {
    }
}
