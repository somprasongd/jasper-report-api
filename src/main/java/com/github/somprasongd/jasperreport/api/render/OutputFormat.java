package com.github.somprasongd.jasperreport.api.render;

import com.github.somprasongd.jasperreport.api.web.ApiException;

import java.util.Locale;

/** What {@code format} in a render request can ask for. */
public enum OutputFormat {

    PDF("pdf", "application/pdf", true),
    XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", false),
    CSV("csv", "text/csv; charset=UTF-8", false);

    private final String extension;
    private final String contentType;
    private final boolean inline;

    OutputFormat(String extension, String contentType, boolean inline) {
        this.extension = extension;
        this.contentType = contentType;
        this.inline = inline;
    }

    public String extension() {
        return extension;
    }

    public String contentType() {
        return contentType;
    }

    /** PDF opens in the browser; spreadsheets are downloaded. */
    public boolean inline() {
        return inline;
    }

    /** Blank means pdf (what {@code jasperreports-pdf} clients send). */
    public static OutputFormat parse(String format) {
        if (format == null || format.isBlank()) {
            return PDF;
        }
        String wanted = format.trim().toLowerCase(Locale.ROOT);
        for (OutputFormat candidate : values()) {
            if (candidate.extension.equals(wanted)) {
                return candidate;
            }
        }
        throw ApiException.badRequest("FORMAT_UNSUPPORTED", "format '" + format + "' is not supported (use pdf, xlsx or csv)");
    }
}
