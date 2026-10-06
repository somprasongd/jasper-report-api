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
    private final long maxBytes;
    private final Path workDir;
    private final int keepVersions;
    private final HttpClient client;

    HttpBundleSource(ReportProperties.Http settings, long maxBytes, Path workDir, int keepVersions) {
        this.settings = settings;
        this.maxBytes = maxBytes;
        this.workDir = workDir.resolve("http");
        this.keepVersions = keepVersions;
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(settings.timeout())
                .build();
    }

    ResolvedBundle resolve(String mainUrl, List<SubReportSource> subReports, List<String> localeHints) {
        Map<String, byte[]> files = new TreeMap<>();
        Map<String, URI> jrxmls = new LinkedHashMap<>();

        URI main = checkedUri(mainUrl);
        files.put("main.jrxml", downloadRequired(main, "report"));
        jrxmls.put("main.jrxml", main);

        for (SubReportSource sub : subReports) {
            String base = ReportCompiler.safeFileName(sub.baseName());
            String fileName = base + ".jrxml";
            if (base.equals("main") || jrxmls.containsKey(fileName)) {
                throw ApiException.badRequest("VALIDATION_FAILED", "sub-report name '" + base + "' is used twice or is reserved");
            }
            URI uri = checkedUri(sub.url());
            files.put(fileName, downloadRequired(uri, "sub-report '" + base + "'"));
            jrxmls.put(fileName, uri);
        }

        Set<String> suffixes = languageSuffixes(localeHints, new String(files.get("main.jrxml"), StandardCharsets.UTF_8));
        for (Map.Entry<String, URI> jrxml : jrxmls.entrySet()) {
            fetchMessageBundles(new String(files.get(jrxml.getKey()), StandardCharsets.UTF_8), jrxml.getValue(), suffixes, files);
        }
        if (files.size() > MAX_FILES) {
            throw ApiException.badRequest("SOURCE_NOT_ALLOWED", "more than " + MAX_FILES + " files for one report");
        }
        return store(mainUrl, subReports, files);
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

    private void fetchMessageBundles(String jrxmlText, URI jrxmlUri, Set<String> suffixes, Map<String, byte[]> files) {
        Matcher declared = RESOURCE_BUNDLE.matcher(jrxmlText);
        if (!declared.find()) {
            return;
        }
        if (jrxmlUri.getRawQuery() != null) {
            log.warn("Message bundles are not downloaded for {} (URL has a query string, e.g. a pre-signed URL)", jrxmlUri.getHost());
            return;
        }
        String base = declared.group(1);
        List<String> candidates = new ArrayList<>();
        candidates.add(base + ".properties");
        suffixes.forEach(suffix -> candidates.add(base + "_" + suffix + ".properties"));
        for (String candidate : candidates) {
            if (files.containsKey(candidate)) {
                continue;
            }
            byte[] content = downloadOptional(checkedUri(jrxmlUri.resolve(candidate).toString()));
            if (content != null) {
                files.put(candidate, content);
            }
        }
    }

    private ResolvedBundle store(String mainUrl, List<SubReportSource> subReports, Map<String, byte[]> files) {
        MessageDigest digest = Hashing.sha256();
        files.forEach((name, content) -> {
            digest.update((name + "|" + Hashing.hex(content, 16) + "\n").getBytes(StandardCharsets.UTF_8));
        });
        String version = Hashing.hex(digest, 16);
        String identity = mainUrl + subReports.stream().map(SubReportSource::url).toList();
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
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (settings.allowedHosts().stream().noneMatch(h -> h.equalsIgnoreCase(host))) {
            throw ApiException.badRequest("SOURCE_NOT_ALLOWED",
                    "host '" + host + "' is not in report.sources.http.allowed-hosts");
        }
        return uri;
    }

    private byte[] downloadRequired(URI uri, String what) {
        Fetched response = get(uri);
        if (response.status() == 404) {
            throw new ApiException(HttpStatus.NOT_FOUND, "REPORT_NOT_FOUND", what + " not found at " + uri.getHost() + uri.getRawPath());
        }
        if (response.status() != 200) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "STORAGE_ERROR", what + " host answered HTTP " + response.status());
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
            throw new ApiException(HttpStatus.BAD_GATEWAY, "STORAGE_ERROR", "host answered HTTP " + status + " for " + uri.getRawPath());
        }
        return response.body();
    }

    private Fetched get(URI uri) {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(settings.timeout()).GET().build();
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
            throw new ApiException(HttpStatus.BAD_GATEWAY, "STORAGE_ERROR", "cannot download " + uri.getHost() + uri.getRawPath() + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.BAD_GATEWAY, "STORAGE_ERROR", "interrupted while downloading a report", e);
        }
    }

    private record Fetched(int status, byte[] body) {
    }
}
