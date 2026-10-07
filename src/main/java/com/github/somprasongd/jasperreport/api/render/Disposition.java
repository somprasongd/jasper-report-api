package com.github.somprasongd.jasperreport.api.render;

import com.github.somprasongd.jasperreport.api.web.ApiException;

import java.util.Locale;

/** What {@code disposition} in a render request can ask for: show the document in the browser or save it. */
public enum Disposition {

    INLINE,
    ATTACHMENT;

    /** Blank means the default of the format ({@link OutputFormat#inline()}). */
    public static boolean inline(String disposition, OutputFormat format) {
        if (disposition == null || disposition.isBlank()) {
            return format.inline();
        }
        String wanted = disposition.trim().toUpperCase(Locale.ROOT);
        for (Disposition candidate : values()) {
            if (candidate.name().equals(wanted)) {
                return candidate == INLINE;
            }
        }
        throw ApiException.badRequest("DISPOSITION_INVALID", "disposition '" + disposition + "' is not supported (use inline or attachment)");
    }
}
