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
package com.lms.identity.web;

import java.net.URI;
import java.util.UUID;

import com.lms.identity.command.NumberSeriesService;
import com.lms.identity.command.domain.SeriesFormat;
import com.lms.identity.numbering.DocumentType;
import com.lms.identity.query.FormatPreviewView;
import com.lms.identity.query.NumberPreviewView;
import com.lms.identity.query.NumberReleaseView;
import com.lms.identity.query.NumberSeriesQueryService;
import com.lms.identity.query.NumberSeriesScopeView;
import com.lms.identity.query.NumberSeriesView;
import com.lms.identity.query.NumberVerificationView;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.query.Slice;
import com.lms.shared.web.IdResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Document number series: how a tenant numbers its orders, consignment notes,
 * dockets and the rest. Guards live on the services.
 */
@RestController
@RequestMapping("/api/v1/number-series")
public class NumberSeriesController {

    private final NumberSeriesService commands;
    private final NumberSeriesQueryService queries;

    public NumberSeriesController(NumberSeriesService commands, NumberSeriesQueryService queries) {
        this.commands = commands;
        this.queries = queries;
    }

    @GetMapping
    public Slice<NumberSeriesView> list(@RequestParam(required = false) DocumentType documentType,
                                        @RequestParam(required = false) String cursor,
                                        @RequestParam(required = false) Integer limit) {
        return queries.list(documentType, cursor, limit);
    }

    @PostMapping
    public ResponseEntity<IdResponse> create(@Valid @RequestBody CreateSeriesRequest request) {
        UUID id = commands.create(request.code(), request.documentType(), request.orgUnitId(),
                request.format(), Boolean.TRUE.equals(request.reuseReleased()),
                request.active() == null || request.active(), request.description());
        return ResponseEntity.created(URI.create("/api/v1/number-series/" + id)).body(new IdResponse(id));
    }

    @GetMapping("/{id}")
    public NumberSeriesView get(@PathVariable UUID id) {
        return queries.findById(id).orElseThrow(() -> new ResourceNotFoundException("NumberSeries", id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Void> revise(@PathVariable UUID id, @Valid @RequestBody ReviseSeriesRequest request) {
        commands.revise(id, request.format(), request.reuseReleased(), request.description());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        commands.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/activate")
    public ResponseEntity<Void> activate(@PathVariable UUID id) {
        commands.activate(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/deactivate")
    public ResponseEntity<Void> deactivate(@PathVariable UUID id) {
        commands.deactivate(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/preview")
    public NumberPreviewView preview(@PathVariable UUID id) {
        return queries.preview(id);
    }

    @PostMapping("/{id}/issue")
    public ResponseEntity<IssuedNumber> issue(@PathVariable UUID id) {
        return ResponseEntity.status(HttpStatus.CREATED).body(new IssuedNumber(commands.issue(id)));
    }

    @GetMapping("/{id}/releases")
    public Slice<NumberReleaseView> releases(@PathVariable UUID id,
                                             @RequestParam(required = false) String cursor,
                                             @RequestParam(required = false) Integer limit) {
        return queries.releases(id, cursor, limit);
    }

    @PostMapping("/{id}/releases")
    public ResponseEntity<Void> release(@PathVariable UUID id, @Valid @RequestBody ReleaseRequest request) {
        commands.release(id, request.number(), request.reason());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/verify")
    public NumberVerificationView verify(@RequestParam DocumentType documentType, @RequestParam String number) {
        return queries.verify(documentType, number);
    }

    @GetMapping("/effective")
    public NumberSeriesView effective(@RequestParam DocumentType documentType,
                                      @RequestParam(required = false) UUID orgUnitId) {
        return queries.effective(documentType, orgUnitId);
    }

    @GetMapping("/format-preview")
    public FormatPreviewView formatPreview(@RequestParam String prefix,
                                           @RequestParam(required = false) SeriesFormat.YearCode yearCode,
                                           @RequestParam(required = false) String separator,
                                           @RequestParam(required = false) Integer counterLength,
                                           @RequestParam(required = false) Integer yearStartMonth,
                                           @RequestParam(required = false) String timeZone) {
        return queries.formatPreview(prefix, yearCode, separator, counterLength, yearStartMonth, timeZone);
    }

    @GetMapping("/scopes")
    public Slice<NumberSeriesScopeView> scopes(@RequestParam(required = false) String cursor,
                                               @RequestParam(required = false) Integer limit) {
        return queries.scopes(cursor, limit);
    }

    /** The number a manual issue handed out. */
    public record IssuedNumber(String number) {
    }

    /**
     * @param orgUnitId     the unit whose subtree the series numbers; absent for the whole tenant
     * @param separator     absent for '-'; an empty string for none
     * @param counterLength absent for 5
     * @param yearStartMonth absent for 4 (April)
     * @param timeZone      absent for UTC
     * @param active        absent for true
     */
    public record CreateSeriesRequest(@NotBlank @Size(max = 48) String code,
                                      @NotNull DocumentType documentType,
                                      UUID orgUnitId,
                                      @NotBlank @Size(max = 16) String prefix,
                                      @NotNull SeriesFormat.YearCode yearCode,
                                      @Size(max = 1) String separator,
                                      @Min(1) @Max(12) Integer counterLength,
                                      @Min(1) @Max(12) Integer yearStartMonth,
                                      @Size(max = 64) String timeZone,
                                      Boolean reuseReleased,
                                      Boolean active,
                                      @Size(max = 500) String description) {

        SeriesFormat format() {
            return formatOf(prefix, yearCode, separator, counterLength, yearStartMonth, timeZone);
        }
    }

    /** Every field restated; the format may differ only while the series has issued nothing. */
    public record ReviseSeriesRequest(@NotBlank @Size(max = 16) String prefix,
                                      @NotNull SeriesFormat.YearCode yearCode,
                                      @Size(max = 1) String separator,
                                      @Min(1) @Max(12) Integer counterLength,
                                      @Min(1) @Max(12) Integer yearStartMonth,
                                      @Size(max = 64) String timeZone,
                                      // Boxed and required: a revision that left it out would
                                      // otherwise switch reuse off.
                                      @NotNull Boolean reuseReleased,
                                      @Size(max = 500) String description) {

        SeriesFormat format() {
            return formatOf(prefix, yearCode, separator, counterLength, yearStartMonth, timeZone);
        }
    }

    public record ReleaseRequest(@NotBlank @Size(max = 64) String number,
                                 @NotBlank @Size(max = 500) String reason) {
    }

    private static SeriesFormat formatOf(String prefix, SeriesFormat.YearCode yearCode, String separator,
                                         Integer counterLength, Integer yearStartMonth, String timeZone) {
        return new SeriesFormat(prefix == null ? null : prefix.trim(), yearCode,
                separator == null ? "-" : separator, counterLength == null ? 5 : counterLength,
                yearStartMonth == null ? 4 : yearStartMonth, SeriesFormat.zoneOf(timeZone));
    }
}
