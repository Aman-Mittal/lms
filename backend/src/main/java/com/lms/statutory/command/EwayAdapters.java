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
package com.lms.statutory.command;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.lms.statutory.api.EwayBillAdapter;
import com.lms.statutory.api.EwayBillRequest;
import com.lms.statutory.api.EwayBillResponse;
import org.springframework.stereotype.Component;

/**
 * The adapters by code, and the one way an adapter is called: on a worker
 * thread, with a deadline. An adapter holds no tenant scope and no
 * transaction there, so a slow statutory system cannot hold a database
 * connection, and a call that overruns is abandoned and reported as a failure.
 */
@Component
public class EwayAdapters {

    /** A call that could not be completed: it timed out or failed in transport. */
    public static final class CallFailed extends RuntimeException {

        private final String code;

        CallFailed(String code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    private final Map<String, EwayBillAdapter> byCode;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

    public EwayAdapters(List<EwayBillAdapter> adapters) {
        this.byCode = adapters.stream().collect(Collectors.toUnmodifiableMap(
                adapter -> adapter.code().toUpperCase(java.util.Locale.ROOT), Function.identity()));
    }

    /** The adapter for a setting value, or empty for NONE or a code nothing implements. */
    public Optional<EwayBillAdapter> find(String code) {
        return Optional.ofNullable(code == null ? null : byCode.get(code.toUpperCase(java.util.Locale.ROOT)));
    }

    /**
     * Calls the adapter, waiting at most {@code timeout}.
     *
     * @throws CallFailed {@code eway-adapter-timeout} or {@code eway-adapter-failed}
     */
    public EwayBillResponse call(EwayBillAdapter adapter, EwayBillRequest request, Duration timeout) {
        Future<EwayBillResponse> pending = workers.submit(() -> adapter.execute(request));
        try {
            EwayBillResponse response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (response == null) {
                throw new CallFailed("eway-adapter-failed", "The adapter gave no answer", null);
            }
            return response;
        } catch (TimeoutException e) {
            pending.cancel(true);
            throw new CallFailed("eway-adapter-timeout",
                    "The e-way bill system did not answer within " + timeout.toSeconds() + " seconds", e);
        } catch (ExecutionException e) {
            throw new CallFailed("eway-adapter-failed", "The e-way bill adapter failed", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pending.cancel(true);
            throw new CallFailed("eway-adapter-failed", "The call was interrupted", e);
        }
    }
}
