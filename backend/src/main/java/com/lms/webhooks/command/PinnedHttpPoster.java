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
package com.lms.webhooks.command;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Posts one webhook to the address the guard checked, and nowhere else.
 *
 * <p>The JDK's {@code HttpClient} takes a URL and resolves its host itself when
 * it connects, which reopens the gap the guard closes: a name re-pointed to an
 * internal address after the check would be followed. This client never
 * resolves anything. It opens the socket to the checked {@link InetAddress}
 * and keeps the subscriber's host name for everything that identifies the
 * server: the {@code Host} header, the TLS server name (SNI) and the
 * certificate check, which is the JDK's standard HTTPS hostname verification
 * against that name.
 *
 * <p>Deliberately small: one HTTP/1.1 POST with {@code Connection: close}, no
 * redirects, no keep-alive, and only the status line of the answer is read.
 * The connect timeout bounds the connection and the read timeout bounds each
 * read of the answer.
 */
class PinnedHttpPoster {

    private static final int MAX_STATUS_LINE = 1024;

    private final SSLSocketFactory tls;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    PinnedHttpPoster(SSLSocketFactory tls, Duration connectTimeout, Duration readTimeout) {
        this.tls = tls;
        this.connectTimeoutMs = Math.toIntExact(connectTimeout.toMillis());
        this.readTimeoutMs = Math.toIntExact(readTimeout.toMillis());
    }

    /**
     * Posts the body with the given headers.
     *
     * @return the status code the receiver answered with
     */
    int post(WebhookTargetGuard.Target target, Map<String, String> headers, byte[] body) throws IOException {
        URI uri = target.uri();
        boolean https = "https".equals(uri.getScheme().toLowerCase(Locale.ROOT));
        int port = uri.getPort() != -1 ? uri.getPort() : (https ? 443 : 80);
        String host = bare(uri.getHost());
        try (Socket socket = open(target.address(), host, port, https)) {
            socket.setSoTimeout(readTimeoutMs);
            OutputStream out = new BufferedOutputStream(socket.getOutputStream());
            out.write(requestHead(uri, port, https, headers, body.length).getBytes(StandardCharsets.US_ASCII));
            out.write(body);
            out.flush();
            return readStatus(socket.getInputStream());
        }
    }

    private Socket open(InetAddress address, String host, int port, boolean https) throws IOException {
        Socket raw = connect(new InetSocketAddress(address, port), connectTimeoutMs);
        if (!https) {
            return raw;
        }
        try {
            // Layered on the socket already connected to the checked address; the
            // host given here is only the TLS peer name, never looked up.
            SSLSocket ssl = (SSLSocket) tls.createSocket(raw, host, port, true);
            ssl.setSSLParameters(tlsParameters(ssl.getSSLParameters(), host));
            ssl.startHandshake();
            return ssl;
        } catch (IOException | RuntimeException e) {
            raw.close();
            throw e;
        }
    }

    /** Opens the TCP connection. Separate so a test can see where a delivery would go. */
    Socket connect(InetSocketAddress address, int timeoutMs) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(address, timeoutMs);
            return socket;
        } catch (IOException e) {
            socket.close();
            throw e;
        }
    }

    /**
     * Verifies the certificate against the subscriber's host name, the way an
     * HTTPS client does, and names that host to the server (SNI). An address
     * literal is not a valid server name, so it is sent without one and the
     * certificate is checked against the address.
     */
    static SSLParameters tlsParameters(SSLParameters parameters, String host) {
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        if (!isAddressLiteral(host)) {
            parameters.setServerNames(List.of(new SNIHostName(host)));
        }
        return parameters;
    }

    static String requestHead(URI uri, int port, boolean https, Map<String, String> headers, int contentLength) {
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        if (uri.getRawQuery() != null) {
            path = path + "?" + uri.getRawQuery();
        }
        boolean defaultPort = port == (https ? 443 : 80);
        StringBuilder head = new StringBuilder()
                .append("POST ").append(path).append(" HTTP/1.1\r\n")
                .append("Host: ").append(uri.getHost()).append(defaultPort ? "" : ":" + port).append("\r\n")
                .append("Content-Length: ").append(contentLength).append("\r\n")
                .append("Connection: close\r\n");
        headers.forEach((name, value) -> head.append(requireToken(name)).append(": ")
                .append(requireHeaderValue(value)).append("\r\n"));
        return head.append("\r\n").toString();
    }

    /** Reads {@code HTTP/1.x NNN ...} and returns {@code NNN}. */
    static int readStatus(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int previous = -1;
        while (true) {
            int b = in.read();
            if (b == -1) {
                throw new IOException("The receiver closed the connection without answering");
            }
            if (previous == '\r' && b == '\n') {
                break;
            }
            if (line.size() >= MAX_STATUS_LINE) {
                throw new IOException("The receiver's status line is too long");
            }
            line.write(b);
            previous = b;
        }
        String status = line.toString(StandardCharsets.US_ASCII).strip();
        String[] parts = status.split(" ", 3);
        if (parts.length < 2 || !parts[0].startsWith("HTTP/1.") || parts[1].length() != 3) {
            throw new IOException("The receiver did not answer with an HTTP status line");
        }
        try {
            return Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            throw new IOException("The receiver did not answer with an HTTP status code", e);
        }
    }

    private static String requireToken(String name) {
        if (name.isEmpty() || !name.chars().allMatch(c -> c > 32 && c < 127 && c != ':')) {
            throw new IllegalArgumentException("Not a header name: " + name);
        }
        return name;
    }

    private static String requireHeaderValue(String value) {
        if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("A header value may not contain a line break");
        }
        return value;
    }

    /** The host without the brackets an IPv6 literal carries in a URL. */
    private static String bare(String host) {
        return host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
    }

    private static boolean isAddressLiteral(String host) {
        return host.indexOf(':') >= 0 || host.chars().allMatch(c -> c == '.' || (c >= '0' && c <= '9'));
    }
}
