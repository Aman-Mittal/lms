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
package com.lms.telematics.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Accepts position reports.
 *
 * <p>A batch, always. Devices buffer through tunnels and dead zones and then
 * flush thirty points at once, so a one-point-per-call interface would turn one
 * reconnection into thirty round trips -- and on a 0.1-CPU instance that is the
 * difference between keeping up and falling behind.
 */
public interface PingIngestPort {

    /**
     * Ingests a batch and reports what happened to it.
     *
     * <p>Never throws for a bad point. A device that reports one implausible
     * coordinate must not lose the twenty-nine good ones alongside it, and the
     * caller cannot fix the bad one anyway -- so the rejection is counted and
     * described, not raised.
     */
    IngestResult ingest(List<PingReport> pings);

    /**
     * One position report.
     *
     * @param recordedAt when the <em>device</em> recorded it, not when it
     *                   arrived. Arrival order is not position order, and
     *                   treating it as such produces a track that jumps
     *                   backwards through time.
     */
    record PingReport(
            UUID vehicleId,
            Instant recordedAt,
            BigDecimal lat,
            BigDecimal lon,
            BigDecimal speedKph,
            BigDecimal headingDeg,
            BigDecimal accuracyM,
            Boolean ignitionOn,
            String source) {
    }

    /**
     * @param rejections why each discarded point was discarded, so a fleet
     *                   manager can tell a failing device from a failing driver
     */
    record IngestResult(int accepted, int rejected, List<Rejection> rejections) {

        public IngestResult {
            rejections = rejections == null ? List.of() : List.copyOf(rejections);
        }
    }

    record Rejection(UUID vehicleId, Instant recordedAt, String reason) {
    }
}
