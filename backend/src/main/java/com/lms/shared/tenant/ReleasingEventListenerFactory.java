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

import java.lang.reflect.Method;

import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalApplicationListenerMethodAdapter;
import org.springframework.transaction.event.TransactionalEventListenerFactory;

/**
 * Creates the {@code @TransactionalEventListener} adapters in place of
 * Spring's own, so that an after-commit listener runs once the committing
 * thread has given its connection back rather than while it still holds it.
 * See {@link AfterConnectionRelease} for why.
 *
 * <p>Ordered ahead of Spring's factory (50), which therefore never sees these
 * methods. The adapter is Spring's in every other respect: the same listener
 * id, so the publication registry and {@code ListenerFailureRecorder} still
 * agree on which listener a publication belongs to, the same phase, condition
 * and fallback handling, and the same registration with the transaction. The
 * one difference is that the processing it is asked to do while a transaction
 * on its thread is completing is deferred until that thread holds no
 * connection. Processing it is asked to do at any other time -- the registry
 * resubmitting an incomplete publication -- runs at once, as before.
 *
 * <p>A {@code BEFORE_COMMIT} listener is not deferred: it must run inside the
 * transaction it belongs to.
 */
public class ReleasingEventListenerFactory extends TransactionalEventListenerFactory {

    /** Ahead of Spring's {@link TransactionalEventListenerFactory}, whose default is 50. */
    public static final int ORDER = 40;

    public ReleasingEventListenerFactory() {
        setOrder(ORDER);
    }

    @Override
    public ApplicationListener<?> createApplicationListener(String beanName, Class<?> type, Method method) {
        return new Adapter(beanName, type, method);
    }

    /** Spring's adapter, deferring after-completion processing until the connection is back. */
    static final class Adapter extends TransactionalApplicationListenerMethodAdapter {

        Adapter(String beanName, Class<?> targetClass, Method method) {
            super(beanName, targetClass, method);
        }

        @Override
        public void processEvent(ApplicationEvent event) {
            if (getTransactionPhase() != TransactionPhase.BEFORE_COMMIT
                    && AfterConnectionRelease.defer(() -> super.processEvent(event))) {
                return;
            }
            super.processEvent(event);
        }
    }
}
