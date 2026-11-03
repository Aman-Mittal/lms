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

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;

import com.lms.shared.error.BusinessRuleViolationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Keeps webhook deliveries pointed at the outside world.
 *
 * <p>A webhook is a request the platform makes on a tenant's say-so, so an
 * address that resolves to the platform's own network -- loopback, private
 * ranges, link-local (which includes cloud metadata services) -- would turn
 * it into a way to probe or call internal services. The address is checked
 * when a subscription is saved and again, after a fresh DNS lookup, before
 * every delivery, because a name can be re-pointed after it was accepted.
 *
 * <p>A delivery connects to the very address that was checked: the name is
 * resolved once, every address it resolves to is judged, and
 * {@link #requireDeliverable} hands back one of them for the connection
 * ({@link PinnedHttpPoster}). Nothing resolves the name again between the check
 * and the connection, so re-pointing the name in that gap (DNS rebinding)
 * cannot redirect a delivery to an internal address.
 */
@Component
public class WebhookTargetGuard {

    private static final int MAX_URL_LENGTH = 2000;

    /** Turns a host name into its addresses. The JDK's resolver, except in tests. */
    @FunctionalInterface
    interface HostResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    /**
     * Where a delivery goes.
     *
     * @param uri     the address as the subscriber wrote it; its host is what the
     *                {@code Host} header, TLS server name and certificate check use
     * @param address the checked address to connect to, so the name is not looked up again
     */
    public record Target(URI uri, InetAddress address) {
    }

    private final boolean allowHttp;
    private final boolean allowPrivateTargets;
    private final HostResolver resolver;

    @Autowired
    public WebhookTargetGuard(@Value("${lms.webhooks.allow-http:false}") boolean allowHttp,
                              @Value("${lms.webhooks.allow-private-targets:false}") boolean allowPrivateTargets) {
        this(allowHttp, allowPrivateTargets, InetAddress::getAllByName);
    }

    WebhookTargetGuard(boolean allowHttp, boolean allowPrivateTargets, HostResolver resolver) {
        this.allowHttp = allowHttp;
        this.allowPrivateTargets = allowPrivateTargets;
        this.resolver = resolver;
    }

    /** Checks an address being saved: scheme, shape and where it resolves to. */
    public URI requireAcceptable(String url) {
        return requireDeliverable(url).uri();
    }

    /**
     * Checks an address about to be called and pins the connection to it: the
     * name is resolved here, once, and the returned address is the one to
     * connect to.
     */
    public Target requireDeliverable(String url) {
        URI uri = parse(url);
        InetAddress[] addresses = resolve(uri.getHost());
        if (!allowPrivateTargets) {
            for (InetAddress address : addresses) {
                if (!isPublic(address)) {
                    throw new BusinessRuleViolationException("webhook-target-not-allowed",
                            "The webhook host resolves to an address that is not on the public internet");
                }
            }
        }
        return new Target(uri, addresses[0]);
    }

    /**
     * Checks an address about to be called.
     *
     * @return why it may not be called, or null when it may
     */
    public String refusalFor(String url) {
        try {
            requireAcceptable(url);
            return null;
        } catch (BusinessRuleViolationException e) {
            return e.getMessage();
        }
    }

    private URI parse(String url) {
        if (url == null || url.isBlank() || url.length() > MAX_URL_LENGTH) {
            throw new BusinessRuleViolationException("webhook-url-invalid",
                    "A webhook address is an absolute URL of at most " + MAX_URL_LENGTH + " characters");
        }
        URI uri;
        try {
            uri = new URI(url.strip());
        } catch (URISyntaxException e) {
            throw new BusinessRuleViolationException("webhook-url-invalid", "That is not a valid URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (uri.getHost() == null || !(scheme.equals("https") || scheme.equals("http"))) {
            throw new BusinessRuleViolationException("webhook-url-invalid",
                    "A webhook address is an absolute http(s) URL with a host");
        }
        if (uri.getUserInfo() != null) {
            throw new BusinessRuleViolationException("webhook-url-invalid",
                    "A webhook address may not carry a user name or password");
        }
        if (scheme.equals("http") && !allowHttp) {
            throw new BusinessRuleViolationException("webhook-url-not-https",
                    "A webhook address must use https");
        }
        return uri;
    }

    private InetAddress[] resolve(String host) {
        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(host);
        } catch (UnknownHostException e) {
            throw unresolvable(host);
        }
        if (addresses == null || addresses.length == 0) {
            throw unresolvable(host);
        }
        return addresses;
    }

    private static BusinessRuleViolationException unresolvable(String host) {
        return new BusinessRuleViolationException("webhook-url-unresolvable",
                "The webhook host " + host + " does not resolve");
    }

    /** Whether an address is on the public internet, as far as a webhook is concerned. */
    static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        byte[] b = address.getAddress();
        if (address instanceof Inet4Address) {
            int first = b[0] & 0xff;
            int second = b[1] & 0xff;
            // 100.64.0.0/10, carrier-grade NAT; 0.0.0.0/8; 192.0.0.0/24 protocol assignments;
            // 198.18.0.0/15 benchmarking.
            return !(first == 0
                    || (first == 100 && second >= 64 && second <= 127)
                    || (first == 192 && second == 0 && (b[2] & 0xff) == 0)
                    || (first == 198 && (second == 18 || second == 19)));
        }
        if (address instanceof Inet6Address) {
            int first = b[0] & 0xff;
            // fc00::/7 unique local addresses; an IPv4-mapped address is judged as its IPv4 address.
            if ((first & 0xfe) == 0xfc) {
                return false;
            }
            if (isIpv4Mapped(b)) {
                try {
                    return isPublic(InetAddress.getByAddress(new byte[] {b[12], b[13], b[14], b[15]}));
                } catch (UnknownHostException e) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean isIpv4Mapped(byte[] b) {
        for (int i = 0; i < 10; i++) {
            if (b[i] != 0) {
                return false;
            }
        }
        return (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff;
    }
}
