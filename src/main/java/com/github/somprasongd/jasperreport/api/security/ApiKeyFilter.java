package com.github.somprasongd.jasperreport.api.security;

import com.github.somprasongd.jasperreport.api.config.ReportProperties.ApiKeyMode;
import com.github.somprasongd.jasperreport.api.web.ProblemJson;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * Enforces {@code X-API-Key} (or {@code Authorization: Bearer <key>}, handy for Prometheus scrapers).
 * A key that is presented but wrong is always rejected, even in {@code optional} mode.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ApiKeyFilter extends OncePerRequestFilter {

    public static final String MDC_CLIENT_ID = "clientId";
    public static final String ANONYMOUS = "anonymous";
    public static final String REQUEST_ATTRIBUTE = ApiKeyFilter.class.getName() + ".clientId";

    private final ApiKeyService keys;
    private final MeterRegistry meters;

    public ApiKeyFilter(ApiKeyService keys, MeterRegistry meters) {
        this.keys = keys;
        this.meters = meters;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return path.equals("/healthz") || path.startsWith("/actuator/health") || path.equals("/v1/openapi.yaml");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String clientId;
        String presented = presentedKey(request);
        if (keys.mode() == ApiKeyMode.DISABLED) {
            clientId = ANONYMOUS;
        } else if (presented == null) {
            if (keys.mode() == ApiKeyMode.REQUIRED) {
                reject(response, "API_KEY_MISSING", "Missing API key (header " + keys.headerName() + ")");
                return;
            }
            clientId = ANONYMOUS;
        } else {
            Optional<String> client = keys.authenticate(presented);
            if (client.isEmpty()) {
                reject(response, "API_KEY_INVALID", "Unknown, disabled or expired API key");
                return;
            }
            clientId = client.get();
        }
        request.setAttribute(REQUEST_ATTRIBUTE, clientId);
        MDC.put(MDC_CLIENT_ID, clientId);
        meters.counter("report.requests", "client", clientId).increment();
        chain.doFilter(request, response);
    }

    private String presentedKey(HttpServletRequest request) {
        String key = request.getHeader(keys.headerName());
        if (key != null && !key.isBlank()) {
            return key.trim();
        }
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String bearer = authorization.substring(7).trim();
            return bearer.isEmpty() ? null : bearer;
        }
        return null;
    }

    private void reject(HttpServletResponse response, String code, String detail) throws IOException {
        meters.counter("report.requests.rejected", "code", code).increment();
        ProblemJson.write(response, HttpStatus.UNAUTHORIZED, code, detail);
    }
}
