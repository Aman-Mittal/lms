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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.ExtendedSSLSession;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The client that connects to the checked address: where it connects, what it
 * sends, and that TLS still checks the certificate against the host name.
 */
class PinnedHttpPosterTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    /** Records where it was asked to connect and refuses, so nothing leaves the machine. */
    private static final class Recording extends PinnedHttpPoster {

        final List<InetSocketAddress> connections = new ArrayList<>();

        Recording() {
            super(null, TIMEOUT, TIMEOUT);
        }

        @Override
        Socket connect(InetSocketAddress address, int timeoutMs) throws IOException {
            connections.add(address);
            throw new ConnectException("refused by the test");
        }
    }

    @Test
    @DisplayName("a name re-pointed after the check is never connected to: the connection goes to the checked address")
    void connectsToTheCheckedAddressOnly() throws Exception {
        InetAddress publicAddress = InetAddress.getByName("203.0.113.10");
        InetAddress internal = InetAddress.getByName("127.0.0.1");
        List<InetAddress> answers = new CopyOnWriteArrayList<>(List.of(publicAddress, internal, internal));
        WebhookTargetGuard guard = new WebhookTargetGuard(false, false,
                host -> new InetAddress[] {answers.remove(0)});
        Recording poster = new Recording();

        WebhookTargetGuard.Target target = guard.requireDeliverable("https://hooks.example.test:8443/hook");
        assertThatThrownBy(() -> poster.post(target, Map.of(), new byte[0])).isInstanceOf(ConnectException.class);

        assertThat(poster.connections).containsExactly(new InetSocketAddress(publicAddress, 8443));
        assertThat(answers).as("the poster looked nothing up").hasSize(2);
    }

    @Test
    @DisplayName("the request names the subscriber's host, closes the connection, and carries the given headers")
    void requestHead() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-Lms-Event", "PING");
        String head = PinnedHttpPoster.requestHead(URI.create("https://hooks.example.test/a/b?c=d"), 443, true,
                headers, 12);
        assertThat(head).isEqualTo("POST /a/b?c=d HTTP/1.1\r\nHost: hooks.example.test\r\nContent-Length: 12\r\n"
                + "Connection: close\r\nX-Lms-Event: PING\r\n\r\n");
        assertThat(PinnedHttpPoster.requestHead(URI.create("http://127.0.0.1:8099"), 8099, false, Map.of(), 0))
                .startsWith("POST / HTTP/1.1\r\nHost: 127.0.0.1:8099\r\n");
        assertThatThrownBy(() -> PinnedHttpPoster.requestHead(URI.create("https://h.test/"), 443, true,
                Map.of("X-A", "one\r\nX-Injected: two"), 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("only the status code of the answer is read")
    void statusLine() throws IOException {
        assertThat(PinnedHttpPoster.readStatus(stream("HTTP/1.1 204 No Content\r\nX: y\r\n\r\n"))).isEqualTo(204);
        assertThat(PinnedHttpPoster.readStatus(stream("HTTP/1.0 503\r\n"))).isEqualTo(503);
        assertThatThrownBy(() -> PinnedHttpPoster.readStatus(stream("SSH-2.0-banner\r\n")))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> PinnedHttpPoster.readStatus(stream(""))).isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("TLS verifies the certificate against the host name and names it to the server; an address literal sends no name")
    void tlsParameters() {
        SSLParameters named = PinnedHttpPoster.tlsParameters(new SSLParameters(), "hooks.example.test");
        assertThat(named.getEndpointIdentificationAlgorithm()).isEqualTo("HTTPS");
        assertThat(named.getServerNames()).containsExactly(new SNIHostName("hooks.example.test"));
        SSLParameters literal = PinnedHttpPoster.tlsParameters(new SSLParameters(), "203.0.113.10");
        assertThat(literal.getEndpointIdentificationAlgorithm()).isEqualTo("HTTPS");
        assertThat(literal.getServerNames()).isNullOrEmpty();
    }

    @Test
    @DisplayName("over real TLS to 127.0.0.1: the certificate for the host name is accepted, any other name is refused")
    void certificateIsCheckedAgainstTheHostName(@TempDir Path dir) throws Exception {
        String storePassword = UUID.randomUUID().toString();
        Path keyStoreFile = dir.resolve("receiver.p12");
        generateCertificate(keyStoreFile, storePassword, "hooks.test");
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keyStoreFile)) {
            keys.load(in, storePassword.toCharArray());
        }

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keys, storePassword.toCharArray());
        SSLContext server = SSLContext.getInstance("TLS");
        server.init(kmf.getKeyManagers(), null, null);

        // The client trusts this one certificate and nothing else.
        KeyStore trusted = KeyStore.getInstance(KeyStore.getDefaultType());
        trusted.load(null, null);
        Certificate certificate = keys.getCertificate("receiver");
        trusted.setCertificateEntry("receiver", certificate);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trusted);
        SSLContext client = SSLContext.getInstance("TLS");
        client.init(null, tmf.getTrustManagers(), null);

        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        List<String> serverNames = new CopyOnWriteArrayList<>();
        List<String> hostHeaders = new CopyOnWriteArrayList<>();
        try (SSLServerSocket listener = (SSLServerSocket) server.getServerSocketFactory()
                .createServerSocket(0, 5, loopback)) {
            Thread acceptor = new Thread(() -> serve(listener, serverNames, hostHeaders), "tls-receiver");
            acceptor.setDaemon(true);
            acceptor.start();
            int port = listener.getLocalPort();
            PinnedHttpPoster poster = new PinnedHttpPoster(client.getSocketFactory(), TIMEOUT, TIMEOUT);

            int status = poster.post(new WebhookTargetGuard.Target(
                    URI.create("https://hooks.test:" + port + "/hook"), loopback),
                    Map.of("X-Lms-Event", "PING"), "{}".getBytes(StandardCharsets.UTF_8));
            assertThat(status).isEqualTo(204);
            assertThat(serverNames).containsExactly("hooks.test");
            assertThat(hostHeaders).containsExactly("hooks.test:" + port);

            // Same address, same certificate, a different name: the check is by name, so it fails.
            assertThatThrownBy(() -> poster.post(new WebhookTargetGuard.Target(
                    URI.create("https://other.test:" + port + "/hook"), loopback), Map.of(), new byte[0]))
                    .isInstanceOf(SSLHandshakeException.class);
        }
    }

    private static void serve(SSLServerSocket listener, List<String> serverNames, List<String> hostHeaders) {
        while (!listener.isClosed()) {
            try (SSLSocket socket = (SSLSocket) listener.accept()) {
                socket.setSoTimeout((int) TIMEOUT.toMillis());
                socket.startHandshake();
                for (SNIServerName name : ((ExtendedSSLSession) socket.getSession()).getRequestedServerNames()) {
                    serverNames.add(new String(name.getEncoded(), StandardCharsets.US_ASCII));
                }
                String head = readHead(socket.getInputStream());
                for (String line : head.split("\r\n", -1)) {
                    if (line.regionMatches(true, 0, "Host:", 0, 5)) {
                        hostHeaders.add(line.substring(5).strip());
                    }
                }
                OutputStream out = socket.getOutputStream();
                out.write("HTTP/1.1 204 No Content\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        .getBytes(StandardCharsets.US_ASCII));
                out.flush();
            } catch (IOException e) {
                // A refused handshake ends that connection; keep serving the next one.
            }
        }
    }

    private static String readHead(InputStream in) throws IOException {
        StringBuilder head = new StringBuilder();
        while (!head.toString().endsWith("\r\n\r\n")) {
            int b = in.read();
            if (b == -1) {
                break;
            }
            head.append((char) b);
        }
        return head.toString();
    }

    /** A certificate for {@code hostName}, made with the JDK's own keytool, so the test needs no extra library. */
    private static void generateCertificate(Path keyStore, String password, String hostName) throws Exception {
        Path keytool = Path.of(System.getProperty("java.home"), "bin", "keytool");
        Process process = new ProcessBuilder(keytool.toString(), "-genkeypair", "-alias", "receiver",
                "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=" + hostName,
                "-ext", "SAN=dns:" + hostName, "-validity", "2", "-storetype", "PKCS12",
                "-keystore", keyStore.toString(), "-storepass", password, "-keypass", password)
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).as("keytool finished").isTrue();
        assertThat(process.exitValue()).as(output).isZero();
    }

    private static InputStream stream(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.US_ASCII));
    }
}
