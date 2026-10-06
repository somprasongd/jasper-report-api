package com.github.somprasongd.jasperreport.api.web;

import com.github.somprasongd.jasperreport.api.inspect.ReportInspector;
import com.github.somprasongd.jasperreport.api.render.RenderRequest;
import com.github.somprasongd.jasperreport.api.render.RenderResult;
import com.github.somprasongd.jasperreport.api.render.RenderService;
import jakarta.validation.Valid;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
@RequestMapping("/v1")
public class ReportController {

    private final RenderService renderService;
    private final ReportInspector inspector;

    public ReportController(RenderService renderService, ReportInspector inspector) {
        this.renderService = renderService;
        this.inspector = inspector;
    }

    /** {@code /jasper/generate} keeps clients of {@code jasperreports-pdf} working unchanged. */
    @PostMapping({"/reports/render", "/jasper/generate"})
    public ResponseEntity<byte[]> render(@Valid @RequestBody RenderRequest request,
                                         @RequestHeader(value = "X-Tenant-Id", required = false) String tenant) {
        RenderResult result = renderService.render(request, tenant);
        ContentDisposition.Builder disposition = result.inline() ? ContentDisposition.inline() : ContentDisposition.attachment();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(result.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.filename(result.fileName(), StandardCharsets.UTF_8).build().toString())
                .header("X-Report-Version", result.version())
                .header(HttpHeaders.CONTENT_LANGUAGE, result.locale())
                .body(result.content());
    }

    /** Compiles the report and describes it; does not run the query or take a render slot. */
    @PostMapping("/reports/validate")
    public Map<String, Object> validate(@Valid @RequestBody RenderRequest request,
                                        @RequestHeader(value = "X-Tenant-Id", required = false) String tenant) {
        return inspector.inspect(request, tenant);
    }
}
