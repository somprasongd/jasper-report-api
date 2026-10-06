package com.github.somprasongd.jasperreport.api.render;

import com.github.somprasongd.jasperreport.api.config.ReportProperties;
import com.github.somprasongd.jasperreport.api.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Decides the language a report is rendered in: the request's {@code locale}, else the {@code report.locale}
 * property declared in the JRXML, else the global {@code report.locale} setting, else English.
 * The request always wins, like the datasource.
 */
@Component
public class LocaleSelector {

    public static final String REPORT_PROPERTY = "report.locale";
    private static final Pattern TAG = Pattern.compile("[A-Za-z]{2,3}([-_][A-Za-z0-9]{2,8})*");

    private final Locale configured;

    public LocaleSelector(ReportProperties properties) {
        String value = properties.locale();
        this.configured = value == null || value.isBlank() ? null : parse(value, "report.locale setting", null);
    }

    public Locale select(String fromRequest, String fromReport) {
        if (fromRequest != null && !fromRequest.isBlank()) {
            return parse(fromRequest, "locale", HttpStatus.BAD_REQUEST);
        }
        if (fromReport != null && !fromReport.isBlank()) {
            return parse(fromReport, "property " + REPORT_PROPERTY + " of the report", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        return configured != null ? configured : Locale.ENGLISH;
    }

    private static Locale parse(String value, String what, HttpStatus status) {
        String tag = value.trim();
        if (!TAG.matcher(tag).matches()) {
            String message = what + " '" + value + "' is not a language tag such as th, en, th-TH or en-US";
            if (status == null) {
                throw new IllegalStateException(message);
            }
            throw new ApiException(status, "LOCALE_INVALID", message);
        }
        return Locale.forLanguageTag(tag.replace('_', '-'));
    }
}
