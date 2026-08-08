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
 */package com.lms.masterdata.web;

import java.util.List;

import com.lms.masterdata.query.ComplianceDocumentView;
import com.lms.masterdata.query.MasterDataQueryService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The screen that stops a dispatch being refused at the gate.
 *
 * <p>Its own resource rather than a filter on a per-owner listing, because the
 * question it answers spans owners: what, anywhere in this tenant, is about to
 * stop a vehicle leaving. Answering that by paging three separate listings and
 * merging them client-side would be three round trips to produce a list that
 * is already one sort.
 */
@RestController
@RequestMapping("/api/v1/compliance")
public class ComplianceController {

    private final MasterDataQueryService masterData;

    public ComplianceController(MasterDataQueryService masterData) {
        this.masterData = masterData;
    }

    @GetMapping("/expiring")
    public List<ComplianceDocumentView> expiring(
            @RequestParam(defaultValue = "30") @Min(0) @Max(3650) int withinDays) {
        return masterData.documentsExpiringWithin(withinDays);
    }
}
