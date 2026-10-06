package com.github.somprasongd.jasperreport.api.render;

import com.github.somprasongd.jasperreport.api.compile.LazySubreports;
import com.github.somprasongd.jasperreport.api.compile.ReportCompiler;
import com.github.somprasongd.jasperreport.api.config.ReportProperties;
import com.github.somprasongd.jasperreport.api.datasource.DataSourceRegistry;
import com.github.somprasongd.jasperreport.api.params.ParameterBinder;
import com.github.somprasongd.jasperreport.api.source.ResolvedBundle;
import com.github.somprasongd.jasperreport.api.source.SourceResolver;
import com.github.somprasongd.jasperreport.api.source.SubReportSource;
import com.github.somprasongd.jasperreport.api.web.ApiException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import net.sf.jasperreports.engine.DefaultJasperReportsContext;
import net.sf.jasperreports.engine.JREmptyDataSource;
import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JRParameter;
import net.sf.jasperreports.engine.JasperFillManager;
import net.sf.jasperreports.engine.JasperPrint;
import net.sf.jasperreports.engine.JasperReport;
import net.sf.jasperreports.engine.util.JRResourcesUtil;
import net.sf.jasperreports.engine.util.LocalJasperReportsContext;
import net.sf.jasperreports.engine.export.JRCsvExporter;
import net.sf.jasperreports.engine.export.ooxml.JRXlsxExporter;
import net.sf.jasperreports.export.SimpleCsvExporterConfiguration;
import net.sf.jasperreports.export.SimpleExporterInput;
import net.sf.jasperreports.export.SimpleOutputStreamExporterOutput;
import net.sf.jasperreports.export.SimpleWriterExporterOutput;
import net.sf.jasperreports.export.SimpleXlsxReportConfiguration;
import net.sf.jasperreports.pdf.JRPdfExporter;
import net.sf.jasperreports.governors.MaxPagesGovernorException;
import net.sf.jasperreports.json.query.JsonQueryExecuterFactory;
import net.sf.jasperreports.governors.TimeoutGovernorException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
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
    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private final DataSourceRegistry datasources;
    private final DatasourceSelector selector;
    private final LocaleSelector localeSelector;
    private final BundleClassLoaders classLoaders;
    private final RenderVirtualizers virtualizers;
    private final SourceResolver sources;
    private final ReportCompiler compiler;
    private final ParameterBinder binder;
    private final ReportProperties properties;
    private final MeterRegistry meters;
    private final Semaphore permits;
    private final AtomicInteger inflight = new AtomicInteger();

    public RenderService(DataSourceRegistry datasources, DatasourceSelector selector, LocaleSelector localeSelector, BundleClassLoaders classLoaders, RenderVirtualizers virtualizers,
                         SourceResolver sources, ReportCompiler compiler, ParameterBinder binder, ReportProperties properties, MeterRegistry meters) {
        this.datasources = datasources;
        this.selector = selector;
        this.localeSelector = localeSelector;
        this.classLoaders = classLoaders;
        this.virtualizers = virtualizers;
        this.sources = sources;
        this.compiler = compiler;
        this.binder = binder;
        this.properties = properties;
        this.meters = meters;
        this.permits = new Semaphore(properties.limits().maxConcurrentRenders(), true);
        meters.gauge("report.inflight", inflight);
    }

    public RenderResult render(RenderRequest request, String tenantHeader) {
        OutputFormat format = OutputFormat.parse(request.format());
        String tenant = datasources.requestedTenant(tenantHeader != null && !tenantHeader.isBlank() ? tenantHeader : request.tenant());
        String reportName = "unknown";
        String datasourceName = "unknown";
        String outcome = "error";
        long start = System.nanoTime();
        acquire();
        try (RenderVirtualizers.Swap swap = virtualizers.open()) {
            ResolvedBundle bundle = sources.resolve(request.mainReport().url(), subReportSources(request.subReports()),
                    localeSelector.hints(request.locale()));
            reportName = stripExtension(bundle.mainFile());
            JasperReport report = compiler.main(bundle);
            DataPlan plan = selector.plan(tenant, request, report, reportName);
            datasourceName = plan.datasource();
            Locale locale = localeSelector.select(request.locale(), report.getProperty(LocaleSelector.REPORT_PROPERTY));

            Map<String, Object> params = new HashMap<>(binder.bind(report, request.parameters()));
            addSystemParameters(params, report, bundle, request, locale);

            if (swap.virtualizer() != null) {
                params.put(JRParameter.REPORT_VIRTUALIZER, swap.virtualizer());
            }

            JasperPrint print = fill(report, params, plan, request, tenant, bundle);
            swap.readOnly();
            byte[] content = export(print, format);
            outcome = "success";
            return new RenderResult(content, format.contentType(), fileName(request, reportName, format), bundle.version(),
                    locale.toLanguageTag(), format.inline());
        } catch (ApiException e) {
            outcome = e.code();
            throw e;
        } finally {
            inflight.decrementAndGet();
            permits.release();
            Timer.builder("report.render")
                    .tag("tenant", tenant).tag("report", reportName).tag("datasource", datasourceName)
                    .tag("format", format.extension()).tag("outcome", outcome)
                    .register(meters).record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }

    /** What an http(s) source needs to know about the listed sub-reports; ignored by folders and S3. */
    public static List<SubReportSource> subReportSources(List<RenderRequest.ReportRef> refs) {
        return refs == null ? List.of() : refs.stream().map(r -> new SubReportSource(r.baseName(), r.url())).toList();
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
                    : request.subReports().stream().map(RenderRequest.ReportRef::baseName).toList();
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

    private JasperPrint fill(JasperReport report, Map<String, Object> params, DataPlan plan, RenderRequest request, String tenant,
                             ResolvedBundle bundle) {
        String datasourceName = plan.datasource();
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
        if (plan.kind() == DataPlan.Kind.DATABASE && !limits.queryTimeout().isZero()) {
            // Statement.setQueryTimeout (seconds) works on every JDBC driver and cancels the query in the database;
            // it also covers sub-reports, which fill under the same context
            long seconds = Math.max(1, (limits.queryTimeout().toMillis() + 999) / 1000);
            context.setProperty("net.sf.jasperreports.jdbc.query.timeout", String.valueOf(seconds));
        }
        try {
            JasperFillManager filler = JasperFillManager.getInstance(context);
            return switch (plan.kind()) {
                case DATABASE -> {
                    try (Connection connection = datasources.get(tenant, datasourceName).getConnection()) {
                        yield filler.fill(report, params, connection);
                    }
                }
                // one empty record, so the title/detail bands of a report without a query print once
                case NONE -> filler.fill(report, params, new JREmptyDataSource());
                case JSON -> {
                    params.put(JsonQueryExecuterFactory.JSON_INPUT_STREAM, new ByteArrayInputStream(jsonBytes(request)));
                    yield filler.fill(report, params);
                }
            };
        } catch (SQLException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "DATABASE_ERROR",
                    "database '" + tenant + "/" + datasourceName + "' failed: " + rootMessage(e), e);
        } catch (JRException | RuntimeException e) {
            throw mapFillFailure(e, tenant, datasourceName);
        } finally {
            JRResourcesUtil.resetClassLoader();
        }
    }

    private byte[] jsonBytes(RenderRequest request) {
        byte[] bytes = request.data().toString().getBytes(StandardCharsets.UTF_8);
        long max = properties.limits().maxDataSize().toBytes();
        if (bytes.length > max) {
            throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "DATA_TOO_LARGE",
                    "'data' is " + bytes.length + " bytes, the limit is " + max + " (report.limits.max-data-size)");
        }
        return bytes;
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
            if (t instanceof SQLException sql && isQueryTimeout(sql)) {
                return new ApiException(HttpStatus.GATEWAY_TIMEOUT, "QUERY_TIMEOUT",
                        "query on '" + tenant + "/" + datasourceName + "' exceeded " + properties.limits().queryTimeout()
                                + " (report.limits.query-timeout)", failure);
            }
            if (t instanceof SQLException) {
                return new ApiException(HttpStatus.BAD_GATEWAY, "DATABASE_ERROR",
                        "database '" + tenant + "/" + datasourceName + "' failed: " + rootMessage(t), failure);
            }
        }
        log.error("Report fill failed", failure);
        return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "report fill failed: " + rootMessage(failure), failure);
    }

    /** JDBC timeout exception, or PostgreSQL's query_canceled (what {@code statement_timeout} raises). */
    private static boolean isQueryTimeout(SQLException e) {
        return e instanceof SQLTimeoutException || "57014".equals(e.getSQLState());
    }

    private byte[] export(JasperPrint print, OutputFormat format) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        try {
            switch (format) {
                case PDF -> {
                    JRPdfExporter exporter = new JRPdfExporter();
                    exporter.setExporterInput(new SimpleExporterInput(print));
                    exporter.setExporterOutput(new SimpleOutputStreamExporterOutput(out));
                    exporter.exportReport();
                }
                case XLSX -> {
                    JRXlsxExporter exporter = new JRXlsxExporter();
                    exporter.setExporterInput(new SimpleExporterInput(print));
                    exporter.setExporterOutput(new SimpleOutputStreamExporterOutput(out));
                    SimpleXlsxReportConfiguration configuration = new SimpleXlsxReportConfiguration();
                    // one continuous sheet of real cells: what people want from a spreadsheet, not a copy of the page layout
                    configuration.setOnePagePerSheet(false);
                    configuration.setDetectCellType(true);
                    configuration.setWhitePageBackground(false);
                    configuration.setRemoveEmptySpaceBetweenRows(true);
                    configuration.setRemoveEmptySpaceBetweenColumns(true);
                    exporter.setConfiguration(configuration);
                    exporter.exportReport();
                }
                case CSV -> {
                    if (properties.export().csvBom()) {
                        out.writeBytes(UTF8_BOM);
                    }
                    JRCsvExporter exporter = new JRCsvExporter();
                    exporter.setExporterInput(new SimpleExporterInput(print));
                    exporter.setExporterOutput(new SimpleWriterExporterOutput(out, StandardCharsets.UTF_8.name()));
                    SimpleCsvExporterConfiguration configuration = new SimpleCsvExporterConfiguration();
                    configuration.setFieldDelimiter(",");
                    configuration.setRecordDelimiter("\r\n");
                    exporter.setConfiguration(configuration);
                    exporter.exportReport();
                }
            }
        } catch (JRException | RuntimeException e) {
            log.error("{} export failed", format.extension(), e);
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                    format.extension().toUpperCase(Locale.ROOT) + " export failed: " + rootMessage(e), e);
        }
        return out.toByteArray();
    }

    private static String fileName(RenderRequest request, String reportName, OutputFormat format) {
        String name = request.fileName() != null && !request.fileName().isBlank() ? request.fileName()
                : request.mainReport().name() != null && !request.mainReport().name().isBlank() ? request.mainReport().name()
                : reportName;
        name = name.replaceAll("[\\p{Cntrl}/\\\\:*?\"<>|]", "_").trim();
        if (name.isEmpty()) {
            name = "report";
        }
        String suffix = "." + format.extension();
        return name.toLowerCase(Locale.ROOT).endsWith(suffix) ? name : name + suffix;
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
