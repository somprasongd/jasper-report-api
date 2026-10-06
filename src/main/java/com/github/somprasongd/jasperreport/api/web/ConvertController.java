package com.github.somprasongd.jasperreport.api.web;

import com.github.somprasongd.jasperreport.api.config.ReportProperties;
import com.github.somprasongd.jasperreport.api.jrxml.LegacyJrxmlConverter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import net.sf.jasperreports.engine.xml.JRXmlLoader;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Migration helper: turns a JasperReports 6.x JRXML into the JR 7 syntax this API renders. Meant to be run once per
 * report when it is imported, not on every render, so it takes no render slot and keeps nothing.
 */
@RestController
@RequestMapping("/v1")
public class ConvertController {

    private final LegacyJrxmlConverter converter = new LegacyJrxmlConverter();
    private final long maxBytes;

    public ConvertController(ReportProperties properties) {
        this.maxBytes = properties.sources().maxBytes().toBytes();
    }

    public record ConvertRequest(@NotBlank String jrxml) {
    }

    public record ConvertResponse(String jrxml, boolean alreadyCurrent, List<String> warnings) {
    }

    @PostMapping("/reports/convert")
    public ConvertResponse convert(@Valid @RequestBody ConvertRequest request) {
        if (request.jrxml().getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "JRXML_TOO_LARGE",
                    "'jrxml' is larger than " + maxBytes + " bytes (report.sources.max-bytes)");
        }
        LegacyJrxmlConverter.Result result;
        try {
            result = converter.convert(request.jrxml());
        } catch (LegacyJrxmlConverter.ConversionException e) {
            throw ApiException.badRequest("CONVERT_FAILED", e.getMessage());
        }
        List<String> warnings = new ArrayList<>(result.warnings());
        try {
            JRXmlLoader.load(new ByteArrayInputStream(result.jrxml().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception | LinkageError e) {
            warnings.add("report: the result does not load in JasperReports 7 (" + rootMessage(e) + "); fix the points above or edit it in Jaspersoft Studio 7");
        }
        return new ConvertResponse(result.jrxml(), result.alreadyCurrent(), warnings);
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String message = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
        return message.length() > 300 ? message.substring(0, 300) + "…" : message;
    }
}
