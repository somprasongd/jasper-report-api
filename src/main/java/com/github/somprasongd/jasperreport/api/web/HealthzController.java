package com.github.somprasongd.jasperreport.api.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Liveness probe kept at {@code /api/healthz} like the previous services; no key required. */
@RestController
public class HealthzController {

    @GetMapping("/healthz")
    public Map<String, String> healthz() {
        return Map.of("status", "UP");
    }
}
