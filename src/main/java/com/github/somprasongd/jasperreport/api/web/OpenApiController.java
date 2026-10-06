package com.github.somprasongd.jasperreport.api.web;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Serves the hand-maintained contract from {@code openapi/openapi.yaml}. It is written by hand rather than generated so
 * it can describe what annotations cannot (the problem codes, the three response media types);
 * {@code OpenApiContractTest} fails when it drifts from the code. Public: it holds no secrets and code generators
 * fetch it without a key.
 */
@RestController
@RequestMapping("/v1")
public class OpenApiController {

    private static final MediaType YAML = MediaType.parseMediaType("application/yaml;charset=UTF-8");

    private final String document;

    public OpenApiController() {
        try (var in = new ClassPathResource("openapi/openapi.yaml").getInputStream()) {
            this.document = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("openapi/openapi.yaml is missing from the class path", e);
        }
    }

    @GetMapping("/openapi.yaml")
    public ResponseEntity<String> openApi() {
        return ResponseEntity.ok().contentType(YAML).cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic()).body(document);
    }
}
