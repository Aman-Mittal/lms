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
package com.lms.events.command;

import java.lang.reflect.Method;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.aop.Advisor;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.StaticMethodMatcherPointcut;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Puts {@link ListenerFailureRecorder} around every transactional event
 * listener in the application -- the same listeners the publication registry
 * tracks -- without any of them knowing.
 *
 * <p>An infrastructure advisor, like the registry's own completion advisor, so
 * the proxies that already exist for that one carry this as well. The recorder
 * is looked up lazily: advisors are created before ordinary beans, and pulling
 * a JdbcClient in that early would initialise half the application out of
 * order.
 */
@Configuration(proxyBeanMethods = false)
class ListenerFailureAdvisorConfiguration {

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    static Advisor listenerFailureAdvisor(ObjectProvider<ListenerFailureRecorder> recorder) {
        StaticMethodMatcherPointcut listeners = new StaticMethodMatcherPointcut() {
            @Override
            public boolean matches(Method method, Class<?> targetClass) {
                return AnnotatedElementUtils.hasAnnotation(method, TransactionalEventListener.class);
            }
        };
        DefaultPointcutAdvisor advisor = new DefaultPointcutAdvisor(listeners, new Interceptor(recorder));
        advisor.setOrder(Ordered.LOWEST_PRECEDENCE);
        return advisor;
    }

    /** Lets the failure through untouched, having written it down. */
    static final class Interceptor implements MethodInterceptor {

        private final ObjectProvider<ListenerFailureRecorder> recorder;

        Interceptor(ObjectProvider<ListenerFailureRecorder> recorder) {
            this.recorder = recorder;
        }

        @Override
        public Object invoke(MethodInvocation invocation) throws Throwable {
            try {
                return invocation.proceed();
            } catch (Throwable failure) {
                Object[] arguments = invocation.getArguments();
                if (arguments.length > 0 && arguments[0] != null) {
                    recorder.getObject().record(invocation.getMethod(), arguments[0], failure);
                }
                throw failure;
            }
        }
    }
}
