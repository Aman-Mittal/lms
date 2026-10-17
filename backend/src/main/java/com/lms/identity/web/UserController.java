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
import java.util.List;
import java.util.UUID;

import com.lms.identity.access.SubjectAttributes;
import com.lms.identity.command.AccessPolicyCommandService;
import com.lms.identity.query.AccessQueryService;
import com.lms.identity.query.UserAccessAttributesView;
import com.lms.identity.query.UserQueryService;
import com.lms.identity.query.UserView;
import com.lms.shared.error.ResourceNotFoundException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Users visible to the caller (vision document 3.1).
 *
 * <p>"Visible" is the org hierarchy doing its work: the listing is bounded by
 * the caller's own organisational path, so a branch manager sees their branch
 * and everything under it and nothing above. There is no unfiltered listing to
 * ask for, which is why this controller has no query parameters at all.
 */
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserQueryService users;
    private final AccessQueryService access;
    private final AccessPolicyCommandService accessCommands;

    public UserController(UserQueryService users, AccessQueryService access,
                          AccessPolicyCommandService accessCommands) {
        this.users = users;
        this.access = access;
        this.accessCommands = accessCommands;
    }

    @GetMapping
    public List<UserView> list() {
        return users.findVisible();
    }

    @GetMapping("/{id}")
    public UserView get(@PathVariable UUID id) {
        return users.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("AppUser", id));
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

    public record AccessAttributesRequest(String region, List<String> freightModes,
                                          UUID partnerId, BigDecimal approvalLimit) {
    }
}
