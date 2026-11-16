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

package com.lms.order.web;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.lms.order.api.MaterialClass;
import com.lms.order.command.intake.BulkIntakeService;
import com.lms.order.command.intake.BulkItem;
import com.lms.order.command.intake.BulkResult;
import com.lms.order.command.intake.IntakeFileService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Bulk order intake (issue #229): a batch of orders with a key each, and an
 * order file that is read into one. Both answer 200 with a result for every
 * item or row, because one that failed is not a failed request.
 */
@RestController
@RequestMapping("/api/v1/order-intake")
public class BulkIntakeController {

    private final BulkIntakeService batches;
    private final IntakeFileService files;

    public BulkIntakeController(BulkIntakeService batches, IntakeFileService files) {
        this.batches = batches;
        this.files = files;
    }

    @PostMapping("/batches")
    public BatchResponse submit(@Valid @RequestBody BatchRequest request) {
        List<BulkItem> items = request.items().stream().map(ItemRequest::toItem).toList();
        List<BulkResult> results = batches.submit(request.orgUnitId(), items);
        return new BatchResponse(count(results, BulkResult.Status.CREATED),
                count(results, BulkResult.Status.DUPLICATE), count(results, BulkResult.Status.FAILED), results);
    }

    @PostMapping(value = "/files", consumes = "multipart/form-data")
    public FileResponse upload(@RequestParam("file") MultipartFile file, @RequestParam UUID orgUnitId,
                               @RequestParam UUID customerPartnerId,
                               @RequestParam(required = false) String batchKey) throws IOException {
        IntakeFileService.Outcome outcome = files.upload(orgUnitId, customerPartnerId, file.getOriginalFilename(),
                file.getBytes(), batchKey);
        return new FileResponse(outcome.created(), outcome.duplicates(), outcome.failed(), outcome.rows());
    }

    private static int count(List<BulkResult> results, BulkResult.Status status) {
        return (int) results.stream().filter(r -> r.status() == status).count();
    }

    public record LineRequest(
            @NotNull @Size(min = 1, max = 60) String materialCode,
            @NotNull @Size(min = 1, max = 200) String materialDescription,
            MaterialClass materialClass,
            @Size(max = 20) String hazmatUnCode,
            @NotNull BigDecimal quantity,
            @NotNull @Size(min = 1, max = 20) String uom,
            @NotNull BigDecimal deadWeightKg,
            UUID consigneePartnerId,
            @NotNull UUID destinationTerminalId) {

        BulkItem.Line toLine() {
            return new BulkItem.Line(materialCode, materialDescription, materialClass, hazmatUnCode, quantity, uom,
                    deadWeightKg, consigneePartnerId, destinationTerminalId);
        }
    }

    /** The key is checked by the service, so a missing one fails that item, not the batch. */
    public record ItemRequest(
            String idempotencyKey,
            @NotNull UUID customerPartnerId,
            String orderNo,
            @NotNull UUID originTerminalId,
            Instant requestedPickupAt,
            Instant requestedDeliveryAt,
            @Size(max = 60) String loadType,
            List<@Valid LineRequest> lines) {

        BulkItem toItem() {
            return new BulkItem(idempotencyKey, customerPartnerId, orderNo, originTerminalId, requestedPickupAt,
                    requestedDeliveryAt, loadType,
                    lines == null ? List.of() : lines.stream().map(LineRequest::toLine).toList());
        }
    }

    /** The batch's size is capped by the service ({@code bulk-batch-too-large}), not here. */
    public record BatchRequest(@NotNull UUID orgUnitId, @NotNull List<@Valid ItemRequest> items) {
    }

    public record BatchResponse(int created, int duplicates, int failed, List<BulkResult> results) {
    }

    public record FileResponse(int created, int duplicates, int failed, List<IntakeFileService.RowResult> rows) {
    }
}
