package com.github.somprasongd.jasperreport.api.render;

import com.github.somprasongd.jasperreport.api.params.ParamInput;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Request body of {@code POST /api/v1/reports/render} (also {@code /api/v1/jasper/generate}). Shape-compatible
 * with {@code jasperreports-pdf}: {@code mainReport.modified_at} is accepted and ignored.
 */
public record RenderRequest(
        String tenant,
        String datasource,
        @NotNull @Valid ReportRef mainReport,
        List<@Valid ReportRef> subReports,
        List<@Valid ParamInput> parameters,
        String format,
        String fileName,
        /** Language tag (th, en, th-TH, ...). Wins over the report's own default. */
        String locale) {

    public record ReportRef(String name, @NotBlank String url, Long modified_at) {

        /** File name without extension: the {@code name}, else the last segment of the {@code url}. */
        public String baseName() {
            String value = name != null && !name.isBlank() ? name.trim() : url == null ? "" : url.trim();
            int query = value.indexOf('?');
            if (query >= 0) {
                value = value.substring(0, query);
            }
            value = value.substring(value.lastIndexOf('/') + 1);
            String lower = value.toLowerCase(java.util.Locale.ROOT);
            if (lower.endsWith(".jrxml") || lower.endsWith(".jasper")) {
                value = value.substring(0, value.lastIndexOf('.'));
            }
            return value;
        }
    }
}
