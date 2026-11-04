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
package com.lms.shared.tenant;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Work a thread must not do while it still holds a pooled connection, held
 * back until it holds none.
 *
 * <p>Spring runs after-commit event listeners while the transaction is
 * completing, before the connection goes back to the pool. Spring Modulith's
 * publication registry marks each tracked listener's publication processing
 * and completed in transactions of their own ({@code REQUIRES_NEW}), so at
 * that point every such request needed a second connection while still holding
 * its first. With a pool of five, five requests committing together could each
 * hold one and wait for another until the pool timed out.
 *
 * <p>{@link TenantAwareTransactionManager} counts the transactions a thread has
 * open and whether one is completing. {@link ReleasingEventListenerFactory}'s
 * listeners {@link #defer} themselves while one is, and the transaction manager
 * {@link #runPending runs} them once the thread's last connection is back in
 * the pool -- still on the same thread, in the same order, and before the
 * request answers, so nothing a caller observes moves. Only where the work runs
 * relative to the connection changes.
 *
 * <p>Per thread, because transactions are. A failing piece of work is logged
 * and the rest still run, exactly as Spring treats an after-completion
 * callback that throws: the transaction has committed and cannot be undone.
 */
final class AfterConnectionRelease {

    private static final Logger log = LoggerFactory.getLogger(AfterConnectionRelease.class);

    private static final ThreadLocal<State> STATE = ThreadLocal.withInitial(State::new);

    /** Per thread: transactions open, transactions completing, and what waits for both to reach zero. */
    private static final class State {
        int open;
        int completing;
        List<Runnable> pending = new ArrayList<>();
    }

    private AfterConnectionRelease() {
    }

    /** A transaction began on this thread and holds a connection. */
    static void opened() {
        STATE.get().open++;
    }

    /** A transaction on this thread has committed or rolled back and is running its callbacks. */
    static void completing() {
        STATE.get().completing++;
    }

    /**
     * A transaction on this thread has given its connection back. If it was
     * the last one, runs what was deferred.
     */
    static void released() {
        State state = STATE.get();
        state.open = Math.max(0, state.open - 1);
        state.completing = Math.max(0, state.completing - 1);
        if (state.open == 0) {
            runPending(state);
        }
    }

    /**
     * Holds the work back if a transaction on this thread is completing, so
     * still holds its connection.
     *
     * @return false if nothing is completing and the caller should run it now
     */
    static boolean defer(Runnable work) {
        State state = STATE.get();
        if (state.completing == 0) {
            return false;
        }
        state.pending.add(work);
        return true;
    }

    /**
     * Runs the deferred work. Work that opens and completes transactions of its
     * own defers into a fresh list, which that transaction runs when it
     * releases its connection -- so a listener's own events are handled before
     * the next listener, as they were when everything ran in place.
     */
    private static void runPending(State state) {
        while (!state.pending.isEmpty()) {
            List<Runnable> batch = state.pending;
            state.pending = new ArrayList<>();
            for (Runnable work : batch) {
                try {
                    work.run();
                } catch (RuntimeException | Error e) {
                    log.error("After-commit work threw an exception; the transaction had already committed", e);
                }
            }
        }
    }
}
