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

import java.util.List;
import java.util.UUID;

import com.lms.identity.query.UserQueryService;
import com.lms.identity.query.UserView;
import com.lms.shared.error.ResourceNotFoundException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

    public UserController(UserQueryService users) {
        this.users = users;
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
}
