package com.github.somprasongd.jasperreport.api.source;

import com.github.somprasongd.jasperreport.api.config.ReportProperties;
import com.github.somprasongd.jasperreport.api.web.ApiException;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Downloads a single JRXML from an allow-listed host. No redirects, size and time limited. */
class HttpBundleSource implements BundleSource {

    private final ReportProperties.Http settings;
    private final long maxBytes;
    private final Path workDir;
    private final HttpClient client;

    HttpBundleSource(ReportProperties.Http settings, long maxBytes, Path workDir) {
        this.settings = settings;
        this.maxBytes = maxBytes;
        this.workDir = workDir.resolve("http");
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(settings.timeout())
                .build();
    }

    @Override
    public ResolvedBundle resolve(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("SOURCE_NOT_ALLOWED", "invalid report URL");
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (settings.allowedHosts().stream().noneMatch(h -> h.equalsIgnoreCase(host))) {
            throw ApiException.badRequest("SOURCE_NOT_ALLOWED",
                    "host '" + host + "' is not in report.sources.http.allowed-hosts");
        }
        byte[] content = download(uri);
        String version = Hashing.hex(content, 16);
        Path dir = workDir.resolve(Hashing.hex(url, 8)).resolve(version);
        Path main = dir.resolve("main.jrxml");
        try {
            if (!Files.exists(main)) {
                Files.createDirectories(dir);
                Path tmp = Files.createTempFile(dir, "main", ".tmp");
                Files.write(tmp, content);
                Files.move(tmp, main, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "cannot store downloaded report: " + e.getMessage(), e);
        }
        return new ResolvedBundle("http:" + Hashing.hex(url, 16), dir, "main.jrxml", version);
    }

    private byte[] download(URI uri) {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(settings.timeout()).GET().build();
        try {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() == 404) {
                    throw new ApiException(HttpStatus.NOT_FOUND, "REPORT_NOT_FOUND", "report not found at " + uri.getHost());
                }
                if (response.statusCode() != 200) {
                    throw new ApiException(HttpStatus.BAD_GATEWAY, "STORAGE_ERROR",
                            "report host answered HTTP " + response.statusCode());
                }
                byte[] bytes = body.readNBytes((int) maxBytes + 1);
                if (bytes.length > maxBytes) {
                    throw ApiException.badRequest("SOURCE_NOT_ALLOWED", "report is larger than " + maxBytes + " bytes");
                }
                return bytes;
            }
        } catch (IOException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "STORAGE_ERROR", "cannot download report: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.BAD_GATEWAY, "STORAGE_ERROR", "interrupted while downloading report", e);
        }
    }
}
