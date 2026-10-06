package com.github.somprasongd.jasperreport.api.compile;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.somprasongd.jasperreport.api.config.ReportProperties;
import com.github.somprasongd.jasperreport.api.source.ResolvedBundle;
import com.github.somprasongd.jasperreport.api.web.ApiException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import net.sf.jasperreports.engine.DefaultJasperReportsContext;
import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JasperCompileManager;
import net.sf.jasperreports.engine.JasperReport;
import net.sf.jasperreports.engine.util.JRSaver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Compiles JRXML files of a bundle and keeps the compiled reports in memory, keyed by bundle version, so a
 * report is compiled once per version no matter how many requests arrive concurrently (Caffeine computes a
 * missing key once per key). Failed compilations are remembered briefly so a broken file is not recompiled in
 * a tight loop.
 */
@Component
public class ReportCompiler {

    private static final Logger log = LoggerFactory.getLogger(ReportCompiler.class);
    private static final Pattern SAFE_NAME = Pattern.compile("[\\p{L}\\p{N}_.\\- ]+");

    private final Cache<String, JasperReport> compiled;
    private final Cache<String, ApiException> failures = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(5)).maximumSize(200).build();
    private final Path workDir;
    private final MeterRegistry meters;

    public ReportCompiler(ReportProperties properties, MeterRegistry meters) {
        this.compiled = Caffeine.newBuilder().maximumSize(properties.cache().maxEntries()).build();
        this.workDir = Path.of(properties.cache().workDir()).toAbsolutePath().normalize().resolve("jasper");
        this.meters = meters;
    }

    public JasperReport main(ResolvedBundle bundle) {
        return get(bundle, bundle.mainFile());
    }

    /** Compiles {@code <name>.jrxml} (or {@code name} when it already ends in .jrxml) of the bundle. */
    public JasperReport named(ResolvedBundle bundle, String name) {
        String file = name.toLowerCase(Locale.ROOT).endsWith(".jrxml") ? name : name + ".jrxml";
        return get(bundle, safeFileName(file));
    }

    private JasperReport get(ResolvedBundle bundle, String file) {
        String key = bundle.cacheKey() + "#" + file;
        ApiException failed = failures.getIfPresent(key);
        if (failed != null) {
            throw failed;
        }
        JasperReport hit = compiled.getIfPresent(key);
        if (hit != null) {
            meters.counter("report.cache.requests", "result", "hit").increment();
            return hit;
        }
        try {
            return compiled.get(key, k -> {
                meters.counter("report.cache.requests", "result", "miss").increment();
                return Timer.builder("report.compile").register(meters).record(() -> compile(bundle.dir().resolve(file)));
            });
        } catch (ApiException e) {
            failures.put(key, e);
            throw e;
        }
    }

    private JasperReport compile(Path file) {
        if (!Files.isRegularFile(file)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "REPORT_NOT_FOUND", "report file not found in bundle: " + file.getFileName()
                    + " (for an http(s) report, list each sub-report in subReports[] with its url)");
        }
        try (InputStream in = Files.newInputStream(file)) {
            log.info("Compiling {}", file);
            return JasperCompileManager.getInstance(DefaultJasperReportsContext.getInstance()).compile(in);
        } catch (JRException e) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "REPORT_COMPILE_FAILED",
                    "cannot compile " + file.getFileName() + ": " + rootMessage(e) + formatHint(file, e), e);
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "cannot read " + file + ": " + e.getMessage(), e);
        }
    }

    /**
     * JasperReports 7 cannot read the JRXML format of 6.x and older and reports only "Unable to load report";
     * recognise the old namespace and say what to do about it.
     */
    private static String formatHint(Path file, JRException e) {
        if (e.getCause() != null) {
            return "";
        }
        try (InputStream in = Files.newInputStream(file)) {
            String head = new String(in.readNBytes(2048), java.nio.charset.StandardCharsets.UTF_8);
            if (head.contains("http://jasperreports.sourceforge.net/jasperreports")) {
                return " (this is the JRXML format of JasperReports 6.x or older; open and re-save it in Jaspersoft Studio 7 to convert it)";
            }
        } catch (IOException ignored) {
            // the original error is more useful than a failure while diagnosing it
        }
        return "";
    }

    /**
     * Legacy sub-report support ({@code $P{SUBREPORT_DIR} + "sub.jasper"}): compiles the requested JRXML files
     * (or all other JRXML files of the bundle when none are named) to {@code .jasper} files in a per-version
     * directory, written atomically, and returns that directory.
     */
    public Path subreportDirectory(ResolvedBundle bundle, List<String> requested) {
        Path dir = workDir.resolve(Integer.toHexString(bundle.bundleId().hashCode())).resolve(bundle.version());
        List<String> names = requested == null || requested.isEmpty() ? listOtherJrxml(bundle) : requested;
        boolean lenient = requested == null || requested.isEmpty();
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "cannot create " + dir, e);
        }
        for (String name : names) {
            String base = stripExtension(safeFileName(name));
            Path target = dir.resolve(base + ".jasper");
            if (Files.exists(target)) {
                continue;
            }
            try {
                JasperReport report = named(bundle, base);
                Path tmp = Files.createTempFile(dir, base, ".tmp");
                JRSaver.saveObject(report, tmp.toFile());
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (ApiException e) {
                if (!lenient) {
                    throw e;
                }
                log.warn("Skipping sub-report {} of {}: {}", name, bundle.bundleId(), e.getMessage());
            } catch (JRException | IOException e) {
                throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "cannot save " + target + ": " + e.getMessage(), e);
            }
        }
        return dir;
    }

    private List<String> listOtherJrxml(ResolvedBundle bundle) {
        try (Stream<Path> files = Files.list(bundle.dir())) {
            List<String> names = new ArrayList<>();
            files.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> n.toLowerCase(Locale.ROOT).endsWith(".jrxml") && !n.equals(bundle.mainFile()) && !n.startsWith("."))
                    .sorted()
                    .forEach(names::add);
            return names;
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "cannot list bundle: " + e.getMessage(), e);
        }
    }

    /** Sub-report names come from JRXML expressions or request bodies: plain file names only. */
    public static String safeFileName(String name) {
        if (name == null || !SAFE_NAME.matcher(name).matches() || name.startsWith(".") || name.contains("..")) {
            throw ApiException.badRequest("SOURCE_NOT_ALLOWED", "invalid sub-report name: " + name);
        }
        return name;
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot).toLowerCase(Locale.ROOT);
        return ext.equals(".jrxml") || ext.equals(".jasper") ? name.substring(0, dot) : name;
    }

    static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String message = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
        return message.length() > 600 ? message.substring(0, 600) + "…" : message;
    }
}
