package com.github.somprasongd.jasperreport.api.source;

import com.github.somprasongd.jasperreport.api.config.ReportProperties;
import com.github.somprasongd.jasperreport.api.web.ApiException;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/** Reads bundles from the (read-only mounted) report root. The folder containing the main JRXML is the bundle. */
class LocalBundleSource implements BundleSource {

    private static final int MAX_FILES = 5000;
    private static final int MAX_DEPTH = 8;

    private final Path root;

    LocalBundleSource(ReportProperties.Local local) {
        this.root = Path.of(local.root()).toAbsolutePath().normalize();
    }

    @Override
    public ResolvedBundle resolve(String relative) {
        if (relative == null || relative.isBlank() || relative.startsWith("/") || relative.contains("\\")
                || relative.indexOf('\0') >= 0) {
            throw notAllowed("report path must be relative to the report root: " + relative);
        }
        Path realRoot;
        try {
            realRoot = root.toRealPath();
        } catch (IOException e) {
            throw new ApiException(HttpStatus.NOT_FOUND, "REPORT_NOT_FOUND", "report root " + root + " does not exist");
        }
        Path candidate = realRoot.resolve(relative).normalize();
        if (!candidate.startsWith(realRoot)) {
            throw notAllowed("report path escapes the report root: " + relative);
        }
        if (!candidate.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jrxml")) {
            throw notAllowed("report must be a .jrxml file: " + relative);
        }
        Path real;
        try {
            real = candidate.toRealPath();
        } catch (IOException e) {
            throw new ApiException(HttpStatus.NOT_FOUND, "REPORT_NOT_FOUND", "report not found: " + relative);
        }
        if (!real.startsWith(realRoot)) {
            throw notAllowed("report path escapes the report root (symlink): " + relative);
        }
        if (!Files.isRegularFile(real)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "REPORT_NOT_FOUND", "report not found: " + relative);
        }
        Path dir = real.getParent();
        String relDir = realRoot.relativize(dir).toString().replace('\\', '/');
        return new ResolvedBundle("local:" + (relDir.isEmpty() ? "." : relDir), dir,
                real.getFileName().toString(), fingerprint(dir));
    }

    private static String fingerprint(Path dir) {
        MessageDigest digest = Hashing.sha256();
        try (Stream<Path> walk = Files.walk(dir, MAX_DEPTH)) {
            List<Path> files = walk.filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .sorted()
                    .limit(MAX_FILES + 1L)
                    .toList();
            if (files.size() > MAX_FILES) {
                throw notAllowed("report folder " + dir.getFileName() + " has more than " + MAX_FILES + " files");
            }
            for (Path file : files) {
                digest.update((dir.relativize(file) + "|" + Files.getLastModifiedTime(file).toMillis() + "|"
                        + Files.size(file) + "\n").getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                    "cannot read report folder: " + e.getMessage(), e);
        }
        return Hashing.hex(digest, 16);
    }

    private static ApiException notAllowed(String message) {
        return ApiException.badRequest("SOURCE_NOT_ALLOWED", message);
    }
}
