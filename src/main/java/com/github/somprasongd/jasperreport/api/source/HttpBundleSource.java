package com.github.somprasongd.jasperreport.api.source;

import com.github.somprasongd.jasperreport.api.compile.ReportCompiler;
import com.github.somprasongd.jasperreport.api.config.ReportProperties;
import com.github.somprasongd.jasperreport.api.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds a bundle from allow-listed http(s) URLs: the main JRXML, the sub-reports listed in the request, and the
 * message bundles ({@code <resourceBundle>_<language>.properties}) found next to each JRXML. Nothing else can be
 * discovered from a URL, so sub-reports must be listed and images must be absolute URLs in the JRXML expression.
 * No redirects; every download is size and time limited. The files are stored in a per-version folder.
 */
class HttpBundleSource {

    private static final Logger log = LoggerFactory.getLogger(HttpBundleSource.class);
    private static final int MAX_FILES = 100;
    private static final Pattern RESOURCE_BUNDLE = Pattern.compile("<jasperReport[^>]*?\\sresourceBundle=\"([A-Za-z0-9_\\-]+)\"");
    private static final Pattern REPORT_LOCALE = Pattern.compile("<property\\s+name=\"report\\.locale\"\\s+value=\"([^\"]+)\"");

    private final ReportProperties.Http settings;
    private final HostAllowList allowedHosts;
    private final long maxBytes;
    private final Path workDir;
    private final int keepVersions;
    private final HttpClient client;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore slots;

    HttpBundleSource(ReportProperties.Http settings, long maxBytes, Path workDir, int keepVersions) {
        this.settings = settings;
        this.allowedHosts = new HostAllowList(settings.allowedHosts());
        this.maxBytes = maxBytes;
        this.workDir = workDir.resolve("http");
        this.keepVersions = keepVersions;
        this.slots = new Semaphore(Math.max(1, settings.parallelism()), true);
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(settings.timeout())
                .build();
    }

    ResolvedBundle resolve(String mainUrl, List<SubReportSource> subReports, List<String> localeHints) {
        // validate every URL and name first (cheap, no network), then download the JRXML files in parallel
        Map<String, URI> jrxmls = new LinkedHashMap<>();
        Map<String, String> what = new LinkedHashMap<>();
        jrxmls.put("main.jrxml", checkedUri(mainUrl));
        what.put("main.jrxml", "report");
        for (SubReportSource sub : subReports) {
            String base = ReportCompiler.safeFileName(sub.baseName());
            String fileName = base + ".jrxml";
            if (base.equals("main") || jrxmls.containsKey(fileName)) {
                throw ApiException.badRequest("VALIDATION_FAILED", "sub-report name '" + base + "' is used twice or is reserved");
            }
            jrxmls.put(fileName, checkedUri(sub.url()));
            what.put(fileName, "sub-report '" + base + "'");
        }
        if (jrxmls.size() > MAX_FILES) {
            throw ApiException.badRequest("SOURCE_NOT_ALLOWED", "more than " + MAX_FILES + " files for one report");
        }

        Map<String, byte[]> files = new TreeMap<>();
        Map<String, Future<byte[]>> pending = new LinkedHashMap<>();
        jrxmls.forEach((name, uri) -> pending.put(name, executor.submit(() -> downloadRequired(uri, what.get(name)))));
        awaitAll(pending, files, true);

        // message bundles next to each JRXML, for every language that might be selected, also in parallel
        Set<String> suffixes = languageSuffixes(localeHints, new String(files.get("main.jrxml"), StandardCharsets.UTF_8));
        Map<String, URI> candidates = new LinkedHashMap<>();
        for (Map.Entry<String, URI> jrxml : jrxmls.entrySet()) {
            collectMessageBundles(new String(files.get(jrxml.getKey()), StandardCharsets.UTF_8), jrxml.getValue(), suffixes, files, candidates);
        }
        if (files.size() + candidates.size() > MAX_FILES) {
            throw ApiException.badRequest("SOURCE_NOT_ALLOWED", "more than " + MAX_FILES + " files for one report");
        }
        Map<String, Future<byte[]>> optional = new LinkedHashMap<>();
        candidates.forEach((name, uri) -> optional.put(name, executor.submit(() -> downloadOptional(uri))));
        awaitAll(optional, files, false);
        return store(mainUrl, subReports, files);
    }

    /** Waits for every download; the first failure in submission order wins (the main report first) and cancels the rest. */
    private static void awaitAll(Map<String, Future<byte[]>> pending, Map<String, byte[]> into, boolean required) {
        try {
            for (Map.Entry<String, Future<byte[]>> entry : pending.entrySet()) {
                byte[] content = entry.getValue().get();
                if (content != null) {
                    into.put(entry.getKey(), content);
                } else if (required) {
                    throw new IllegalStateException("no content for " + entry.getKey());
                }
            }
        } catch (ExecutionException e) {
            pending.values().forEach(f -> f.cancel(true));
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new ApiException(HttpStatus.BAD_GATEWAY, "STORAGE_ERROR", "download failed: " + e.getCause(), e.getCause());
        } catch (InterruptedException e) {
            pending.values().forEach(f -> f.cancel(true));
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.BAD_GATEWAY, "STORAGE_ERROR", "interrupted while downloading a report", e);
        }
    }

    /** {@code th-TH} -> {@code th_TH}, {@code th}: the file name suffixes of the languages that could be selected. */
    private static Set<String> languageSuffixes(List<String> hints, String mainJrxml) {
        Set<String> tags = new LinkedHashSet<>(hints);
        Matcher declared = REPORT_LOCALE.matcher(mainJrxml);
        if (declared.find()) {
            tags.add(declared.group(1).trim());
        }
        Set<String> suffixes = new LinkedHashSet<>();
        for (String tag : tags) {
            Locale locale = Locale.forLanguageTag(tag.replace('_', '-'));
            if (!locale.getLanguage().isEmpty() && locale.getLanguage().matches("[a-z]{2,3}")) {
                if (!locale.getCountry().isEmpty()) {
                    suffixes.add(locale.getLanguage() + "_" + locale.getCountry());
                }
                suffixes.add(locale.getLanguage());
            }
        }
        return suffixes;
    }

    private void collectMessageBundles(String jrxmlText, URI jrxmlUri, Set<String> suffixes, Map<String, byte[]> files,
                                       Map<String, URI> candidates) {
        Matcher declared = RESOURCE_BUNDLE.matcher(jrxmlText);
        if (!declared.find()) {
            return;
        }
        if (jrxmlUri.getRawQuery() != null) {
            log.warn("Message bundles are not downloaded for {} (URL has a query string, e.g. a pre-signed URL)", jrxmlUri.getHost());
            return;
        }
        String base = declared.group(1);
        List<String> names = new ArrayList<>();
        names.add(base + ".properties");
        suffixes.forEach(suffix -> names.add(base + "_" + suffix + ".properties"));
        for (String name : names) {
            if (!files.containsKey(name) && !candidates.containsKey(name)) {
                candidates.put(name, checkedUri(jrxmlUri.resolve(name).toString()));
            }
        }
    }

    private ResolvedBundle store(String mainUrl, List<SubReportSource> subReports, Map<String, byte[]> files) {
        MessageDigest digest = Hashing.sha256();
        files.forEach((name, content) -> {
            digest.update((name + "|" + Hashing.hex(content, 16) + "\n").getBytes(StandardCharsets.UTF_8));
        });
        String version = Hashing.hex(digest, 16);
        String identity = identityOf(mainUrl) + subReports.stream().map(s -> identityOf(s.url())).toList();
        Path bundleRoot = workDir.resolve(Hashing.hex(identity, 12));
        Path target = bundleRoot.resolve(version);
        try {
            if (!Files.isDirectory(target)) {
                Files.createDirectories(bundleRoot);
                Path tmp = bundleRoot.resolve(".tmp-" + UUID.randomUUID());
                Files.createDirectories(tmp);
                try {
                    for (Map.Entry<String, byte[]> file : files.entrySet()) {
                        Files.write(tmp.resolve(file.getKey()), file.getValue());
                    }
                    Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.FileAlreadyExistsException | java.nio.file.DirectoryNotEmptyException e) {
                    WorkDirs.deleteRecursively(tmp); // another request stored the same version first
                } catch (IOException | RuntimeException e) {
                    WorkDirs.deleteRecursively(tmp);
                    throw e;
                }
                WorkDirs.prune(bundleRoot, target, keepVersions);
            }
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "cannot store downloaded report: " + e.getMessage(), e);
        }
        return new ResolvedBundle("http:" + Hashing.hex(identity, 16), target, "main.jrxml", version);
    }

    /**
     * The URL without query and fragment. A pre-signed URL names the same object but carries a new signature and
     * expiry on every request; keyed by the full URL each request would get its own bundle id (so a recompile) and
     * its own folder under the work dir. The version is a hash of the content, so editing the object still shows.
     */
    static String identityOf(String url) {
        try {
            URI uri = URI.create(url.trim());
            if (uri.getScheme() == null || uri.getRawAuthority() == null) {
                return url.trim();
            }
            return uri.getScheme().toLowerCase(Locale.ROOT) + "://" + uri.getRawAuthority() + (uri.getRawPath() == null ? "" : uri.getRawPath());
        } catch (IllegalArgumentException e) {
            return url.trim();
        }
    }

    private URI checkedUri(String url) {
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("SOURCE_NOT_ALLOWED", "invalid report URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw ApiException.badRequest("SOURCE_NOT_ALLOWED", "only http(s) URLs are accepted for sub-reports of an http(s) report");
        }
        if (!allowedHosts.allows(uri)) {
            throw ApiException.badRequest("SOURCE_NOT_ALLOWED",
                    "host '" + HostAllowList.describe(uri) + "' is not in report.sources.http.allowed-hosts");
        }
        return uri;
    }

    private byte[] downloadRequired(URI uri, String what) {
        Fetched response = get(uri);
        if (response.status() == 404) {
            throw new ApiException(HttpStatus.NOT_FOUND, "REPORT_NOT_FOUND", what + " not found at " + uri.getHost() + uri.getRawPath());
        }
        if (response.status() != 200) {
            ApiException failure = new ApiException(HttpStatus.BAD_GATEWAY, "STORAGE_ERROR", what + " host answered HTTP " + response.status());
            throw response.status() >= 500 ? failure.transientFailure() : failure;
        }
        return response.body();
    }

    /** A message bundle that is not there is normal (not every language has a file): 403/404 mean "absent". */
    private byte[] downloadOptional(URI uri) {
        Fetched response = get(uri);
        int status = response.status();
        if (status == 404 || status == 403) {
            return null;
        }
        if (status != 200) {
            ApiException failure = new ApiException(HttpStatus.BAD_GATEWAY, "STORAGE_ERROR", "host answered HTTP " + status + " for " + uri.getRawPath());
            throw status >= 500 ? failure.transientFailure() : failure;
        }
        return response.body();
    }

    private Fetched get(URI uri) {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(settings.timeout()).GET().build();
        try {
            slots.acquire(); // bounds the downloads in flight across all requests
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.BAD_GATEWAY, "STORAGE_ERROR", "interrupted while waiting to download a report", e);
        }
        try {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                byte[] bytes = response.statusCode() == 200 ? body.readNBytes((int) maxBytes + 1) : new byte[0];
                if (bytes.length > maxBytes) {
                    throw ApiException.badRequest("SOURCE_NOT_ALLOWED", "a file is larger than " + maxBytes + " bytes: " + uri.getRawPath());
                }
                return new Fetched(response.statusCode(), bytes);
            }
        } catch (IOException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "STORAGE_ERROR", "cannot download " + uri.getHost() + uri.getRawPath() + ": " + e.getMessage(), e).transientFailure();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.BAD_GATEWAY, "STORAGE_ERROR", "interrupted while downloading a report", e);
        } finally {
            slots.release();
        }
    }

    void close() {
        executor.shutdownNow();
    }

    private record Fetched(int status, byte[] body) {
    }
}
