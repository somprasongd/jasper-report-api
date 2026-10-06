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
    }
}
