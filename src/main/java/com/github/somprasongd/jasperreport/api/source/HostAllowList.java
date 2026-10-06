package com.github.somprasongd.jasperreport.api.source;

import java.net.URI;
import java.util.List;
import java.util.Locale;

/**
 * The entries of {@code report.sources.http.allowed-hosts}. An entry is a host name with an optional port:
 * <ul>
 *   <li>{@code files.internal}: that host on any port</li>
 *   <li>{@code files.internal:8080}: only port 8080 (a URL without a port means 80 for http, 443 for https)</li>
 *   <li>{@code *.reports.svc.cluster.local}: every sub-domain at any depth, but not {@code reports.svc.cluster.local}
 *       itself; may have a port too</li>
 *   <li>{@code [::1]:8080}: an IPv6 address in brackets</li>
 * </ul>
 * The host name in the URL is what is checked (case-insensitively); nothing is resolved, so a service name in
 * docker compose or Kubernetes keeps working when its IP changes. A malformed entry fails at startup.
 */
final class HostAllowList {

    private static final String PROPERTY = "report.sources.http.allowed-hosts";

    private final List<Entry> entries;

    HostAllowList(List<String> configured) {
        this.entries = configured.stream().filter(s -> s != null && !s.isBlank()).map(HostAllowList::parse).toList();
    }

    boolean allows(URI uri) {
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (host.isEmpty()) {
            return false;
        }
        int port = effectivePort(uri);
        return entries.stream().anyMatch(e -> e.matches(host, port));
    }

    /** {@code host:port} of the URL as it is matched, for error messages (never the user info or the path). */
    static String describe(URI uri) {
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        return host + ":" + effectivePort(uri);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static Entry parse(String raw) {
        String s = raw.trim().toLowerCase(Locale.ROOT);
        String host = s;
        int port = Entry.ANY_PORT;
        if (s.startsWith("[")) {
            int end = s.indexOf(']');
            if (end < 0) {
                throw invalid(raw);
            }
            host = s.substring(0, end + 1);
            String rest = s.substring(end + 1);
            if (!rest.isEmpty()) {
                if (!rest.startsWith(":")) {
                    throw invalid(raw);
                }
                port = parsePort(rest.substring(1), raw);
            }
        } else {
            int colon = s.indexOf(':');
            if (colon >= 0) {
                if (s.indexOf(':', colon + 1) >= 0) {
                    throw invalid(raw); // an IPv6 address must be in brackets
                }
                host = s.substring(0, colon);
                port = parsePort(s.substring(colon + 1), raw);
            }
        }
        boolean wildcard = host.startsWith("*.");
        String name = wildcard ? host.substring(2) : host;
        if (name.isEmpty() || name.startsWith(".") || name.endsWith(".") || name.contains("..")
                || name.contains("*") || name.contains("/") || name.contains("@") || (wildcard && name.startsWith("["))) {
            throw invalid(raw);
        }
        return new Entry(name, wildcard, port);
    }

    private static int parsePort(String text, String raw) {
        try {
            int port = Integer.parseInt(text);
            if (port >= 1 && port <= 65535) {
                return port;
            }
        } catch (NumberFormatException e) {
            // reported below
        }
        throw invalid(raw);
    }

    private static IllegalArgumentException invalid(String raw) {
        return new IllegalArgumentException("invalid entry '" + raw + "' in " + PROPERTY
                + " (expected host, host:port, *.domain or *.domain:port)");
    }

    private record Entry(String name, boolean wildcard, int port) {

        static final int ANY_PORT = -1;

        boolean matches(String host, int requestPort) {
            if (port != ANY_PORT && port != requestPort) {
                return false;
            }
            return wildcard ? host.endsWith("." + name) : host.equals(name);
        }
    }
}
