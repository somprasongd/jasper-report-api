package com.github.somprasongd.jasperreport.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * All {@code report.*} settings. Defaults are chosen to be safe: no S3, no http(s) sources, API key required.
 */
@ConfigurationProperties(prefix = "report")
public record ReportProperties(
        @DefaultValue("Asia/Bangkok") String timezone,
        /** Empty = keep the JVM default locale (th_TH would print Buddhist-era years in date patterns). */
        @DefaultValue("") String locale,
        @DefaultValue Sources sources,
        @DefaultValue Cache cache,
        @DefaultValue Datasource datasource,
        @DefaultValue Parameters parameters,
        @DefaultValue Limits limits,
        @DefaultValue Security security) {

    public record Sources(
            @DefaultValue Local local,
            @DefaultValue S3 s3,
            @DefaultValue Http http,
            @DefaultValue("5MB") DataSize maxBytes,
            /** Fallback image folder for reports whose bundle has no assets/ sub-folder (IMAGE_DIR). Empty = bundle folder. */
            @DefaultValue("") String imagesDir) {
    }

    public record Local(@DefaultValue("reports") String root) {
    }

    public record S3(
            @DefaultValue("") String endpoint,
            @DefaultValue("us-east-1") String region,
            @DefaultValue("true") boolean pathStyleAccess,
            @DefaultValue("") String accessKey,
            @DefaultValue("") String secretKey,
            @DefaultValue List<String> allowedBuckets,
            @DefaultValue("2000") int maxObjects,
            @DefaultValue("100MB") DataSize maxBundleBytes) {

        public boolean configured() {
            return !endpoint.isBlank() && !allowedBuckets.isEmpty();
        }
    }

    public record Http(@DefaultValue List<String> allowedHosts, @DefaultValue("10s") Duration timeout,
                       /** Downloads in flight at once, across all requests (the files of one report are fetched in parallel). */
                       @DefaultValue("8") int parallelism) {
    }

    public record Cache(
            @DefaultValue("10s") Duration checkInterval,
            @DefaultValue("500") int maxEntries,
            @DefaultValue("cache") String workDir,
            @DefaultValue("3") int keepVersions) {
    }

    public record Datasource(@DefaultValue("true") boolean allowRequestOverride) {
    }

    public record Parameters(@DefaultValue("false") boolean strict) {
    }

    public record Limits(
            @DefaultValue("4") int maxConcurrentRenders,
            @DefaultValue("10s") Duration queueWait,
            @DefaultValue("60s") Duration fillTimeout,
            @DefaultValue("30s") Duration queryTimeout,
            @DefaultValue("500") int maxPages) {
    }

    public record Security(@DefaultValue ApiKey apiKey, @DefaultValue List<ApiKeyEntry> apiKeys) {
    }

    public record ApiKey(@DefaultValue("required") ApiKeyMode mode, @DefaultValue("X-API-Key") String header) {
    }

    public enum ApiKeyMode {
        REQUIRED, OPTIONAL, DISABLED
    }

    public record ApiKeyEntry(String clientId, String sha256, @DefaultValue("true") boolean enabled, OffsetDateTime expiresAt) {
    }
}
