package com.github.somprasongd.jasperreport.api.render;

import com.github.somprasongd.jasperreport.api.compile.LazySubreports;
import com.github.somprasongd.jasperreport.api.compile.ReportCompiler;
import com.github.somprasongd.jasperreport.api.config.ReportProperties;
import com.github.somprasongd.jasperreport.api.datasource.DataSourceRegistry;
import com.github.somprasongd.jasperreport.api.params.ParameterBinder;
import com.github.somprasongd.jasperreport.api.source.ResolvedBundle;
import com.github.somprasongd.jasperreport.api.source.SourceResolver;
import com.github.somprasongd.jasperreport.api.web.ApiException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import net.sf.jasperreports.engine.DefaultJasperReportsContext;
import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JRParameter;
import net.sf.jasperreports.engine.JasperFillManager;
import net.sf.jasperreports.engine.JasperPrint;
import net.sf.jasperreports.engine.JasperReport;
import net.sf.jasperreports.engine.util.JRResourcesUtil;
import net.sf.jasperreports.engine.util.LocalJasperReportsContext;
import net.sf.jasperreports.pdf.JRPdfExporter;
import net.sf.jasperreports.export.SimpleExporterInput;
import net.sf.jasperreports.export.SimpleOutputStreamExporterOutput;
import net.sf.jasperreports.governors.MaxPagesGovernorException;
import net.sf.jasperreports.governors.TimeoutGovernorException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class RenderService {

    private static final Logger log = LoggerFactory.getLogger(RenderService.class);
    private static final String PDF = "application/pdf";

    private final DataSourceRegistry datasources;
    private final DatasourceSelector selector;
    private final LocaleSelector localeSelector;
    private final BundleClassLoaders classLoaders;
    private final SourceResolver sources;
    private final ReportCompiler compiler;
    private final ParameterBinder binder;
    private final ReportProperties properties;
    private final MeterRegistry meters;
    private final Semaphore permits;
    private final AtomicInteger inflight = new AtomicInteger();

    public RenderService(DataSourceRegistry datasources, DatasourceSelector selector, LocaleSelector localeSelector, BundleClassLoaders classLoaders, SourceResolver sources,
                         ReportCompiler compiler, ParameterBinder binder, ReportProperties properties, MeterRegistry meters) {
        this.datasources = datasources;
        this.selector = selector;
        this.localeSelector = localeSelector;
        this.classLoaders = classLoaders;
        this.sources = sources;
        this.compiler = compiler;
        this.binder = binder;
        this.properties = properties;
        this.meters = meters;
        this.permits = new Semaphore(properties.limits().maxConcurrentRenders(), true);
        meters.gauge("report.inflight", inflight);
    }

    public RenderResult render(RenderRequest request, String tenantHeader) {
        String format = request.format() == null || request.format().isBlank() ? "pdf" : request.format().toLowerCase(Locale.ROOT);
        if (!format.equals("pdf")) {
            throw ApiException.badRequest("FORMAT_UNSUPPORTED", "format '" + request.format() + "' is not supported (only pdf)");
        }
        String tenant = datasources.resolveTenant(tenantHeader != null && !tenantHeader.isBlank() ? tenantHeader : request.tenant());
        String reportName = "unknown";
        String datasourceName = "unknown";
        String outcome = "error";
        long start = System.nanoTime();
        acquire();
        try {
            ResolvedBundle bundle = sources.resolve(request.mainReport().url());
            reportName = stripExtension(bundle.mainFile());
            JasperReport report = compiler.main(bundle);
            datasourceName = selector.select(tenant, request.datasource(),
                    report.getProperty(DatasourceSelector.REPORT_PROPERTY), reportName);
            DataSource dataSource = datasources.get(tenant, datasourceName);
            Locale locale = localeSelector.select(request.locale(), report.getProperty(LocaleSelector.REPORT_PROPERTY));

            Map<String, Object> params = new HashMap<>(binder.bind(report, request.parameters()));
            addSystemParameters(params, report, bundle, request, locale);

            JasperPrint print = fill(report, params, dataSource, tenant, datasourceName, bundle);
            byte[] pdf = exportPdf(print);
            outcome = "success";
            return new RenderResult(pdf, PDF, fileName(request, reportName), bundle.version(), locale.toLanguageTag());
        } catch (ApiException e) {
            outcome = e.code();
            throw e;
        } finally {
            inflight.decrementAndGet();
            permits.release();
            Timer.builder("report.render")
                    .tag("tenant", tenant).tag("report", reportName).tag("datasource", datasourceName)
                    .tag("format", format).tag("outcome", outcome)
                    .register(meters).record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }

    private void acquire() {
        try {
            if (!permits.tryAcquire(properties.limits().queueWait().toMillis(), TimeUnit.MILLISECONDS)) {
                meters.counter("report.render.rejected", "reason", "busy").increment();
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "RENDER_BUSY",
                        "all render slots are busy, try again shortly").retryAfter(5);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "RENDER_BUSY", "interrupted while waiting for a render slot").retryAfter(5);
        }
        inflight.incrementAndGet();
    }

    private void addSystemParameters(Map<String, Object> params, JasperReport report, ResolvedBundle bundle, RenderRequest request, Locale locale) {
        List<String> declared = java.util.Arrays.stream(report.getParameters()).map(JRParameter::getName).toList();
        if (declared.contains("SUBREPORTS")) {
            params.put("SUBREPORTS", new LazySubreports(compiler, bundle));
        }
        if (declared.contains("SUBREPORT_DIR")) {
            List<String> names = request.subReports() == null ? List.of()
                    : request.subReports().stream().map(r -> r.name() != null && !r.name().isBlank() ? r.name() : r.url()).toList();
            params.put("SUBREPORT_DIR", compiler.subreportDirectory(bundle, names) + File.separator);
        }
        String assets = assetsDirectory(bundle);
        params.put("IMAGE_DIR", assets);
        params.put("REPORT_ASSETS_DIR", assets);
        params.put(JRParameter.REPORT_TIME_ZONE, TimeZone.getTimeZone(properties.timezone()));
        params.put(JRParameter.REPORT_LOCALE, locale);
        if (declared.contains("REPORT_LANGUAGE")) {
            params.put("REPORT_LANGUAGE", locale.getLanguage());
        }
    }

    private String assetsDirectory(ResolvedBundle bundle) {
        Path assets = bundle.dir().resolve("assets");
        if (Files.isDirectory(assets)) {
            return assets + File.separator;
        }
        String configured = properties.sources().imagesDir();
        if (!configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize() + File.separator;
        }
        return bundle.dir() + File.separator;
    }

    private JasperPrint fill(JasperReport report, Map<String, Object> params, DataSource dataSource, String tenant, String datasourceName,
                             ResolvedBundle bundle) {
        LocalJasperReportsContext context = new LocalJasperReportsContext(DefaultJasperReportsContext.getInstance());
        ReportProperties.Limits limits = properties.limits();
        // message bundles next to the JRXML: JasperReports looks resources up through the thread class loader first
        ClassLoader bundleLoader = classLoaders.forBundle(bundle);
        context.setClassLoader(bundleLoader);
        JRResourcesUtil.setThreadClassLoader(bundleLoader);
        context.setProperty("net.sf.jasperreports.governor.timeout.enabled", "true");
        context.setProperty("net.sf.jasperreports.governor.timeout", String.valueOf(limits.fillTimeout().toMillis()));
        context.setProperty("net.sf.jasperreports.governor.max.pages.enabled", "true");
        context.setProperty("net.sf.jasperreports.governor.max.pages", String.valueOf(limits.maxPages()));
        try (Connection connection = dataSource.getConnection()) {
            return JasperFillManager.getInstance(context).fill(report, params, connection);
        } catch (SQLException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "DATABASE_ERROR",
                    "database '" + tenant + "/" + datasourceName + "' failed: " + rootMessage(e), e);
        } catch (JRException | RuntimeException e) {
            throw mapFillFailure(e, tenant, datasourceName);
        } finally {
            JRResourcesUtil.resetClassLoader();
        }
    }

    private ApiException mapFillFailure(Throwable failure, String tenant, String datasourceName) {
        for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof ApiException api) {
                return api;
            }
            if (t instanceof TimeoutGovernorException) {
                return new ApiException(HttpStatus.GATEWAY_TIMEOUT, "RENDER_TIMEOUT",
                        "report exceeded the fill timeout of " + properties.limits().fillTimeout(), failure);
            }
            if (t instanceof MaxPagesGovernorException) {
                return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PAGE_LIMIT_EXCEEDED",
                        "report exceeded the limit of " + properties.limits().maxPages() + " pages", failure);
            }
            if (t instanceof SQLException) {
                return new ApiException(HttpStatus.BAD_GATEWAY, "DATABASE_ERROR",
                        "database '" + tenant + "/" + datasourceName + "' failed: " + rootMessage(t), failure);
            }
        }
        log.error("Report fill failed", failure);
        return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "report fill failed: " + rootMessage(failure), failure);
    }

    private byte[] exportPdf(JasperPrint print) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        try {
            JRPdfExporter exporter = new JRPdfExporter();
            exporter.setExporterInput(new SimpleExporterInput(print));
            exporter.setExporterOutput(new SimpleOutputStreamExporterOutput(out));
            exporter.exportReport();
        } catch (JRException | RuntimeException e) {
            log.error("PDF export failed", e);
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "PDF export failed: " + rootMessage(e), e);
        }
        return out.toByteArray();
    }

    private static String fileName(RenderRequest request, String reportName) {
        String name = request.fileName() != null && !request.fileName().isBlank() ? request.fileName()
                : request.mainReport().name() != null && !request.mainReport().name().isBlank() ? request.mainReport().name()
                : reportName;
        name = name.replaceAll("[\\p{Cntrl}/\\\\:*?\"<>|]", "_").trim();
        if (name.isEmpty()) {
            name = "report";
        }
        return name.toLowerCase(Locale.ROOT).endsWith(".pdf") ? name : name + ".pdf";
    }

    private static String stripExtension(String file) {
        int dot = file.lastIndexOf('.');
        return dot > 0 ? file.substring(0, dot) : file;
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
