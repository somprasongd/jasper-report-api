package com.github.somprasongd.jasperreport.api.source;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HostAllowListTest {

    private static boolean allows(List<String> entries, String url) {
        return new HostAllowList(entries).allows(URI.create(url));
    }

    @Test
    void plainHostMatchesAnyPortButOnlyThatHost() {
        List<String> list = List.of("report-files");
        assertThat(allows(list, "http://report-files/a.jrxml")).isTrue();
        assertThat(allows(list, "http://REPORT-FILES:8080/a.jrxml")).isTrue();
        assertThat(allows(list, "https://report-files:9443/a.jrxml")).isTrue();
        assertThat(allows(list, "http://report-files.evil.example/a.jrxml")).isFalse();
        assertThat(allows(list, "http://x.report-files/a.jrxml")).isFalse();
        assertThat(allows(list, "http://report-files@evil.example/a.jrxml")).isFalse();
    }

    @Test
    void hostWithPortMatchesOnlyThatPortAndUsesTheSchemeDefault() {
        List<String> list = List.of("files.internal:8080", "secure.internal:443");
        assertThat(allows(list, "http://files.internal:8080/a.jrxml")).isTrue();
        assertThat(allows(list, "http://files.internal/a.jrxml")).isFalse();
        assertThat(allows(list, "http://files.internal:8081/a.jrxml")).isFalse();
        assertThat(allows(list, "https://secure.internal/a.jrxml")).isTrue();
        assertThat(allows(list, "https://secure.internal:443/a.jrxml")).isTrue();
        assertThat(allows(list, "http://secure.internal/a.jrxml")).isFalse();
    }

    @Test
    void wildcardMatchesSubDomainsAtAnyDepthButNotTheDomainItself() {
        List<String> list = List.of("*.reports.svc.cluster.local");
        assertThat(allows(list, "http://files.reports.svc.cluster.local/a.jrxml")).isTrue();
        assertThat(allows(list, "http://a.b.reports.svc.cluster.local:8080/a.jrxml")).isTrue();
        assertThat(allows(list, "http://reports.svc.cluster.local/a.jrxml")).isFalse();
        assertThat(allows(list, "http://evilreports.svc.cluster.local/a.jrxml")).isFalse();
        assertThat(allows(list, "http://files.reports.svc.cluster.local.evil.example/a.jrxml")).isFalse();
    }

    @Test
    void wildcardWithPort() {
        List<String> list = List.of("*.svc.cluster.local:8080");
        assertThat(allows(list, "http://files.reports.svc.cluster.local:8080/a.jrxml")).isTrue();
        assertThat(allows(list, "http://files.reports.svc.cluster.local/a.jrxml")).isFalse();
    }

    @Test
    void ipv6InBrackets() {
        assertThat(allows(List.of("[::1]:8080"), "http://[::1]:8080/a.jrxml")).isTrue();
        assertThat(allows(List.of("[::1]:8080"), "http://[::1]:9090/a.jrxml")).isFalse();
        assertThat(allows(List.of("[::1]"), "http://[::1]:9090/a.jrxml")).isTrue();
    }

    @Test
    void emptyListAndBlankEntriesAllowNothing() {
        assertThat(allows(List.of(), "http://files.internal/a.jrxml")).isFalse();
        assertThat(allows(List.of("", " "), "http://files.internal/a.jrxml")).isFalse();
        // a host name the URI parser cannot read (e.g. an underscore) has no host and is refused
        assertThat(allows(List.of("report_files"), "http://report_files/a.jrxml")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"*", "*.", "*.*.internal", "files*.internal", "files.*.internal", "files.internal:0",
            "files.internal:65536", "files.internal:http", "files.internal:", "::1", "[::1", "[::1]x", "*.[::1]",
            ".internal", "files.internal.", "a..internal", "files.internal/reports", "user@files.internal"})
    void malformedEntriesFailAtStartup(String entry) {
        assertThatThrownBy(() -> new HostAllowList(List.of(entry)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("report.sources.http.allowed-hosts");
    }

    @Test
    void describesTheMatchedHostAndPortWithoutUserInfoOrPath() {
        assertThat(HostAllowList.describe(URI.create("https://u:p@Files.Internal/secret/a.jrxml?sig=x"))).isEqualTo("files.internal:443");
        assertThat(HostAllowList.describe(URI.create("http://files.internal:8080/a.jrxml"))).isEqualTo("files.internal:8080");
    }
}
