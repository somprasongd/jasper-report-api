package com.github.somprasongd.jasperreport.api.source;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.somprasongd.jasperreport.api.config.ReportProperties;
import com.github.somprasongd.jasperreport.api.web.ApiException;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the {@code mainReport.url} of a request into a local {@link ResolvedBundle}.
 * <ul>
 *   <li>no scheme: path relative to {@code report.sources.local.root}</li>
 *   <li>{@code s3://bucket/folder/report.jrxml}: allow-listed bucket on the S3-compatible store</li>
 *   <li>{@code http(s)://host/...}: only hosts from {@code report.sources.http.allowed-hosts}</li>
 * </ul>
 * Resolutions are remembered for {@code report.cache.check-interval} so storage is not hit on every request.
 */
@Component
public class SourceResolver {

    private static final Pattern SCHEME = Pattern.compile("^([a-zA-Z][a-zA-Z0-9+.-]*)://.*", Pattern.DOTALL);

    private final LocalBundleSource local;
    private final S3BundleSource s3;
    private final HttpBundleSource http;
    private final Cache<String, ResolvedBundle> recent;

    public SourceResolver(ReportProperties properties) {
        Path workDir = Path.of(properties.cache().workDir()).toAbsolutePath().normalize();
        ReportProperties.Sources sources = properties.sources();
        this.local = new LocalBundleSource(sources.local());
        this.s3 = new S3BundleSource(sources.s3(), workDir, properties.cache().keepVersions());
        this.http = new HttpBundleSource(sources.http(), sources.maxBytes().toBytes(), workDir, properties.cache().keepVersions());
        this.recent = Caffeine.newBuilder()
                .expireAfterWrite(properties.cache().checkInterval())
                .maximumSize(properties.cache().maxEntries())
                .build();
    }

    public ResolvedBundle resolve(String url) {
        return resolve(url, List.of(), List.of());
    }

    /**
     * @param subReports sub-reports listed in the request; only an http(s) main report needs them
     * @param localeHints language tags whose {@code .properties} files an http(s) source should also download
     */
    public ResolvedBundle resolve(String url, List<SubReportSource> subReports, List<String> localeHints) {
        if (url == null || url.isBlank()) {
            throw ApiException.badRequest("VALIDATION_FAILED", "mainReport.url is required");
        }
        String trimmed = url.trim();
        Matcher matcher = SCHEME.matcher(trimmed);
        String scheme = matcher.matches() ? matcher.group(1).toLowerCase(Locale.ROOT) : "";
        return switch (scheme) {
            case "" -> recent.get(trimmed, local::resolve);
            case "s3" -> recent.get(trimmed, s3::resolve);
            case "http", "https" -> {
                String key = trimmed + "\n" + subReports.stream().map(s -> s.baseName() + "=" + s.url()).toList() + "\n" + localeHints;
                yield recent.get(key, k -> http.resolve(trimmed, subReports, localeHints));
            }
            default -> throw ApiException.badRequest("SOURCE_NOT_ALLOWED", "unsupported scheme in report url");
        };
    }

    /** Throws if the storage backends that are configured cannot be reached (used by readiness). */
    public void probeStorage(ReportProperties.S3 settings) {
        if (settings.configured()) {
            s3.probe();
        }
    }
}
