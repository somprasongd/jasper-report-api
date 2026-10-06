package com.github.somprasongd.jasperreport.api.web;

import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Writes an {@code application/problem+json} body outside Spring MVC (servlet filters). */
public final class ProblemJson {

    public static final String MDC_REQUEST_ID = "requestId";

    private ProblemJson() {
    }

    public static void write(HttpServletResponse response, HttpStatus status, String code, String detail) throws IOException {
        String requestId = MDC.get(MDC_REQUEST_ID);
        String body = "{\"type\":\"about:blank\",\"title\":" + quote(status.getReasonPhrase())
                + ",\"status\":" + status.value()
                + ",\"code\":" + quote(code)
                + ",\"detail\":" + quote(detail)
                + (requestId == null ? "" : ",\"requestId\":" + quote(requestId))
                + "}";
        response.setStatus(status.value());
        response.setContentType("application/problem+json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(body);
    }

    static String quote(String value) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
