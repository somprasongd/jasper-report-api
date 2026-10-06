package com.github.somprasongd.jasperreport.api.render;

import com.github.somprasongd.jasperreport.api.config.ReportProperties;
import com.github.somprasongd.jasperreport.api.datasource.DataSourceRegistry;
import com.github.somprasongd.jasperreport.api.web.ApiException;
import io.micrometer.core.instrument.MeterRegistry;
import net.sf.jasperreports.engine.JasperReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Decides which logical datasource a render uses: request, else the {@code report.datasource} property of the
 * JRXML, else the tenant default (the name {@code none} means no database, and the request's {@code data} means
 * JSON instead of any datasource). When the request and the JRXML disagree the request wins (configurable),
 * and the disagreement is logged and counted so reports with a stale property can be found and fixed.
 */
@Component
public class DatasourceSelector {

    public static final String REPORT_PROPERTY = "report.datasource";
    private static final Logger log = LoggerFactory.getLogger(DatasourceSelector.class);

    private final DataSourceRegistry registry;
    private final boolean allowOverride;
    private final MeterRegistry meters;

    public DatasourceSelector(DataSourceRegistry registry, ReportProperties properties, MeterRegistry meters) {
        this.registry = registry;
        this.allowOverride = properties.datasource().allowRequestOverride();
        this.meters = meters;
    }

    /**
     * Decides where the render gets its rows. The request's {@code data} means JSON; otherwise the datasource name is
     * chosen as described above, {@code none} (or no name at all for a report without a query) means no database.
     */
    public DataPlan plan(String tenant, RenderRequest request, JasperReport report, String reportName) {
        String requested = blankToNull(request.datasource());
        boolean hasQuery = report.getQuery() != null;
        if (request.data() != null && !request.data().isNull()) {
            if (requested != null) {
                throw ApiException.badRequest("DATA_AND_DATASOURCE", "send either 'data' or 'datasource', not both");
            }
            String language = hasQuery ? report.getQuery().getLanguage() : null;
            if (language == null || !language.toLowerCase(Locale.ROOT).startsWith("json")) {
                throw ApiException.badRequest("DATA_NOT_SUPPORTED", "report " + reportName
                        + " has no JSON query (<query language=\"json\">), so it cannot read 'data'");
            }
            return DataPlan.json();
        }
        String name = choose(tenant, requested, blankToNull(report.getProperty(REPORT_PROPERTY)), reportName);
        if (name == null) {
            if (!hasQuery) {
                return DataPlan.none();
            }
            registry.resolveTenant(tenant);
            throw ApiException.badRequest("DATASOURCE_UNRESOLVED", "no datasource in the request, in the report ("
                    + REPORT_PROPERTY + ") or as default of tenant '" + tenant + "'");
        }
        if (DataPlan.isNone(name)) {
            if (hasQuery) {
                throw ApiException.badRequest("DATASOURCE_NONE_NOT_ALLOWED",
                        "report " + reportName + " has a query, so it needs a real datasource ('none' is for reports without one)");
            }
            return DataPlan.none();
        }
        registry.resolveTenant(tenant);
        if (!registry.exists(tenant, name)) {
            throw ApiException.badRequest("DATASOURCE_UNKNOWN",
                    "datasource '" + name + "' is not configured for tenant '" + tenant + "'");
        }
        return DataPlan.database(name);
    }

    /** request, else the JRXML property, else the tenant default; null when none of them names one. */
    private String choose(String tenant, String request, String file, String reportName) {
        if (request != null && file != null && !request.equals(file)) {
            if (!allowOverride) {
                throw ApiException.badRequest("DATASOURCE_OVERRIDE_DENIED", "request datasource '" + request
                        + "' differs from '" + file + "' declared by report " + reportName);
            }
            log.warn("Datasource override: report={} tenant={} request={} report-property={}", reportName, tenant, request, file);
            meters.counter("report.datasource.override", "report", reportName, "from_file", file, "from_request", request).increment();
            return request;
        }
        return request != null ? request : file != null ? file : registry.defaultDatasource(tenant);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
