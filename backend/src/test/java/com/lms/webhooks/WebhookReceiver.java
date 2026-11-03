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
package com.lms.webhooks;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpServer;

/**
 * A local HTTP endpoint standing in for a customer's webhook receiver: it
 * records what it was sent and answers with the status codes it is told to,
 * then with {@link #fallback}.
 */
public final class WebhookReceiver implements AutoCloseable {

    /** One request as received. Header names are lower-cased. */
    public record Received(Map<String, String> headers, String body) {
    }

    private final HttpServer server;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private final Deque<Integer> planned = new ArrayDeque<>();
    private volatile int fallback = 200;

    public WebhookReceiver() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            String body;
            try (InputStream in = exchange.getRequestBody()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            Map<String, String> headers = new java.util.HashMap<>();
            exchange.getRequestHeaders().forEach((name, values) ->
                    headers.put(name.toLowerCase(java.util.Locale.ROOT), values.get(0)));
            received.add(new Received(Map.copyOf(headers), body));
            int status;
            synchronized (planned) {
                Integer next = planned.pollFirst();
                status = next == null ? fallback : next;
            }
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
    }

    /** The next answers, in order; after them, {@link #answerAlways}. */
    public void answer(Integer... statuses) {
        synchronized (planned) {
            planned.addAll(List.of(statuses));
        }
    }

    public void answerAlways(int status) {
        fallback = status;
    }

    public List<Received> received() {
        return List.copyOf(received);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
