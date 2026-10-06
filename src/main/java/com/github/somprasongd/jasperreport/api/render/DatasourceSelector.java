package com.github.somprasongd.jasperreport.api.render;

import com.github.somprasongd.jasperreport.api.config.ReportProperties;
import com.github.somprasongd.jasperreport.api.datasource.DataSourceRegistry;
import com.github.somprasongd.jasperreport.api.web.ApiException;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Decides which logical datasource a render uses: request, else the {@code report.datasource} property of the
 * JRXML, else the tenant default. When the request and the JRXML disagree the request wins (configurable),
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

    public String select(String tenant, String fromRequest, String fromReport, String reportName) {
        String request = blankToNull(fromRequest);
        String file = blankToNull(fromReport);
        String name;
        if (request != null && file != null && !request.equals(file)) {
            if (!allowOverride) {
                throw ApiException.badRequest("DATASOURCE_OVERRIDE_DENIED", "request datasource '" + request
                        + "' differs from '" + file + "' declared by report " + reportName);
            }
            log.warn("Datasource override: report={} tenant={} request={} report-property={}", reportName, tenant, request, file);
            meters.counter("report.datasource.override", "report", reportName, "from_file", file, "from_request", request).increment();
            name = request;
        } else {
            name = request != null ? request : file != null ? file : registry.defaultDatasource(tenant);
        }
        if (name == null) {
            throw ApiException.badRequest("DATASOURCE_UNRESOLVED", "no datasource in the request, in the report ("
                    + REPORT_PROPERTY + ") or as default of tenant '" + tenant + "'");
        }
        if (!registry.exists(tenant, name)) {
            throw ApiException.badRequest("DATASOURCE_UNKNOWN",
                    "datasource '" + name + "' is not configured for tenant '" + tenant + "'");
        }
        return name;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
