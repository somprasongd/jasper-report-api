package com.github.somprasongd.jasperreport.api;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureMetrics
@ActiveProfiles("test")
class RenderApiTest {

    static final String KEY = "jra_test_key_for_unit_tests";

    @Autowired
    MockMvc mvc;

    private MockHttpServletRequestBuilder render(String body) {
        return post("/v1/reports/render").header("X-API-Key", KEY).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static String demoRequest(String extra) {
        return """
                {"mainReport":{"name":"ใบรับรองแพทย์","url":"demo/demo.jrxml"},
                 "parameters":[{"name":"hn","value":"HN001"}%s]}""".formatted(extra);
    }

    @Test
    void rendersThaiTextFontsQrAndBarcodeAndSubreport() throws Exception {
        MvcResult result = mvc.perform(render(demoRequest(",{\"name\":\"print_time\",\"value\":\"2026-10-06T10:30:00+07:00\"}")))
                .andExpect(status().isOk())
                .andReturn();
        byte[] pdf = result.getResponse().getContentAsByteArray();
        assertThat(result.getResponse().getContentType()).isEqualTo("application/pdf");
        assertThat(result.getResponse().getHeader("X-Report-Version")).isNotBlank();
        assertThat(result.getResponse().getHeader("X-Request-Id")).isNotBlank();
        assertThat(result.getResponse().getHeader("Content-Disposition")).contains("filename*=UTF-8''");
        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");

        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertThat(doc.getNumberOfPages()).isEqualTo(1);
            String text = new PDFTextStripper().getText(doc);
            assertThat(text).contains("ใบรับรองแพทย์ทดสอบ").contains("สมชาย").contains("HN001")
                    .contains("จำนวนครั้งที่มารับบริการ: 2")   // sub-report ran its own query
                    .contains("พิมพ์เมื่อ 6 ตุลาคม 2026 10:30");   // +07:00 timestamp kept in Asia/Bangkok, Thai month, Gregorian year

            Set<String> fonts = new HashSet<>();
            for (var name : doc.getPage(0).getResources().getFontNames()) {
                PDFont font = doc.getPage(0).getResources().getFont(name);
                fonts.add(font.getName());
                assertThat(font.isEmbedded()).as("font %s embedded", font.getName()).isTrue();
            }
            assertThat(fonts).anyMatch(n -> n.contains("THSarabunNew"));

            BufferedImage hi = new PDFRenderer(doc).renderImageWithDPI(0, 300);
            double hs = 300 / 72.0;
            assertThat(decode(hi, (int) (15 * hs), (int) (115 * hs), (int) (110 * hs), (int) (110 * hs), BarcodeFormat.QR_CODE))
                    .as("QR content decoded by ZXing (UTF-8, Thai)").isEqualTo("HN:HN001 สมชาย ใจดี");
            assertThat(decode(hi, (int) (160 * hs), (int) (115 * hs), (int) (270 * hs), (int) (70 * hs), BarcodeFormat.CODE_128))
                    .as("Code128 content decoded by ZXing").isEqualTo("HN001");

            BufferedImage page = new PDFRenderer(doc).renderImageWithDPI(0, 100);
            java.nio.file.Files.createDirectories(java.nio.file.Path.of("target/test-output"));
            javax.imageio.ImageIO.write(page, "png", new java.io.File("target/test-output/demo.png"));
            double scale = 100 / 72.0;
            // QR code occupies x 20..120, y 120..220 (points); barcode x 170..420, y 120..180
            assertThat(darkRatio(page, (int) (25 * scale), (int) (125 * scale), (int) (90 * scale), (int) (90 * scale)))
                    .as("QR code region").isBetween(0.15, 0.85);
            assertThat(darkRatio(page, (int) (175 * scale), (int) (125 * scale), (int) (230 * scale), (int) (45 * scale)))
                    .as("Code128 region").isBetween(0.08, 0.85);
            assertThat(darkRatio(page, (int) (490 * scale), (int) (22 * scale), (int) (30 * scale), (int) (30 * scale)))
                    .as("logo from IMAGE_DIR (assets/logo.png)").isGreaterThan(0.0);
        }
    }

    private static String decode(BufferedImage image, int x, int y, int w, int h, BarcodeFormat format) throws Exception {
        java.nio.file.Files.createDirectories(java.nio.file.Path.of("target/test-output"));
        javax.imageio.ImageIO.write(image.getSubimage(x, y, w, h), "png", new java.io.File("target/test-output/crop_" + format + ".png"));
        int[] pixels = image.getRGB(x, y, w, h, null, 0, w);
        var bitmap = new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(w, h, pixels)));
        return new MultiFormatReader().decode(bitmap, Map.of(
                DecodeHintType.POSSIBLE_FORMATS, List.of(format),
                DecodeHintType.CHARACTER_SET, "UTF-8",
                DecodeHintType.TRY_HARDER, Boolean.TRUE)).getText();
    }

    private static double darkRatio(BufferedImage image, int x, int y, int w, int h) {
        int dark = 0;
        int total = 0;
        for (int i = x; i < x + w; i++) {
            for (int j = y; j < y + h; j++) {
                int rgb = image.getRGB(i, j);
                int luminance = (((rgb >> 16) & 0xff) + ((rgb >> 8) & 0xff) + (rgb & 0xff)) / 3;
                if (luminance < 128) {
                    dark++;
                }
                total++;
            }
        }
        return (double) dark / total;
    }

    @Test
    void subreportDirModeCompilesJasperFilesAndSurvivesRepeatRequests() throws Exception {
        String body = """
                {"mainReport":{"url":"subdirmode/main_dir.jrxml"},
                 "parameters":[{"name":"hn","value":"HN001"},{"name":"SUBREPORT_DIR","value":"/etc/"}]}""";
        for (int i = 0; i < 2; i++) {
            MvcResult result = mvc.perform(render(body)).andExpect(status().isOk()).andReturn();
            try (PDDocument doc = Loader.loadPDF(result.getResponse().getContentAsByteArray())) {
                String text = new PDFTextStripper().getText(doc);
                assertThat(text).contains("แบบ SUBREPORT_DIR: สมชาย ใจดี").contains("sub-report (.jasper): visits=2");
            }
        }
    }

    @Test
    void javaAndDefaultExpressionLanguagesCompileWithoutAJdk() throws Exception {
        for (String name : new String[]{"lang_java", "lang_none"}) {
            MvcResult result = mvc.perform(render("{\"mainReport\":{\"url\":\"lang/" + name + ".jrxml\"},\"parameters\":[{\"name\":\"hn\",\"value\":\"HN001\"}]}"))
                    .andExpect(status().isOk()).andReturn();
            try (PDDocument doc = Loader.loadPDF(result.getResponse().getContentAsByteArray())) {
                assertThat(new PDFTextStripper().getText(doc)).contains("expression language " + name.substring(5)).contains("2");
            }
        }
    }

    @Test
    void jasperReports6FormatIsRejectedWithAHelpfulMessage() throws Exception {
        // JasperReports 7.0.8 cannot read 6.x-format JRXML (verified with real production files too)
        mvc.perform(render("""
                        {"mainReport":{"url":"old6/legacy_demo.jrxml"},"parameters":[{"name":"hn","value":"HN001"}]}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("REPORT_COMPILE_FAILED"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Jaspersoft Studio 7")));
    }

    @Test
    void legacyAliasEndpointWorksWithPdfStyleBody() throws Exception {
        mvc.perform(post("/v1/jasper/generate").header("X-API-Key", KEY).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"datasource":"opd","mainReport":{"name":"demo","url":"demo/demo.jrxml","modified_at":1},
                                 "parameters":[{"name":"hn","type":"string","value":"HN001"}]}"""))
                .andExpect(status().isOk());
    }

    @Test
    void apiKeyIsEnforced() throws Exception {
        mvc.perform(post("/v1/reports/render").contentType(MediaType.APPLICATION_JSON).content(demoRequest("")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("API_KEY_MISSING"));
        mvc.perform(post("/v1/reports/render").header("X-API-Key", "jra_wrong").contentType(MediaType.APPLICATION_JSON).content(demoRequest("")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("API_KEY_INVALID"));
        mvc.perform(post("/v1/reports/render").header("Authorization", "Bearer " + KEY).contentType(MediaType.APPLICATION_JSON).content(demoRequest("")))
                .andExpect(status().isOk());
        mvc.perform(get("/healthz")).andExpect(status().isOk());
    }

    @Test
    void validationAndSourceErrorsAreProblemJson() throws Exception {
        mvc.perform(render("{\"mainReport\":{}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(render("{\"mainReport\":{\"url\":\"../secret.jrxml\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SOURCE_NOT_ALLOWED"));
        mvc.perform(render("{\"mainReport\":{\"url\":\"/etc/passwd\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SOURCE_NOT_ALLOWED"));
        mvc.perform(render("{\"mainReport\":{\"url\":\"file:///etc/passwd\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SOURCE_NOT_ALLOWED"));
        mvc.perform(render("{\"mainReport\":{\"url\":\"s3://other/x/y.jrxml\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SOURCE_NOT_ALLOWED"));
        mvc.perform(render("{\"mainReport\":{\"url\":\"https://evil.example/x.jrxml\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SOURCE_NOT_ALLOWED"));
        mvc.perform(render("{\"mainReport\":{\"url\":\"demo/missing.jrxml\"}}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REPORT_NOT_FOUND"));
        mvc.perform(render(demoRequest("").replace("\"parameters\"", "\"format\":\"docx\",\"parameters\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FORMAT_UNSUPPORTED"));
        mvc.perform(render(demoRequest("").replace("\"parameters\"", "\"tenant\":\"nobody\",\"parameters\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TENANT_UNKNOWN"));
    }

    @Test
    void requestDatasourceOverridesReportPropertyAndUnknownNamesAreRejected() throws Exception {
        // report declares opd; the ipd database has no patient table -> proves the request's datasource was used
        mvc.perform(render(demoRequest("").replace("\"parameters\"", "\"datasource\":\"ipd\",\"parameters\"")))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("DATABASE_ERROR"));
        mvc.perform(render(demoRequest("").replace("\"parameters\"", "\"datasource\":\"nope\",\"parameters\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DATASOURCE_UNKNOWN"));
    }

    @Test
    void badParameterValueIsRejectedByName() throws Exception {
        mvc.perform(render(demoRequest(",{\"name\":\"print_time\",\"value\":\"not-a-time\"}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAMETER_INVALID"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("print_time")));
    }

    @Test
    void validateDescribesTheReport() throws Exception {
        mvc.perform(post("/v1/reports/validate").header("X-API-Key", KEY).contentType(MediaType.APPLICATION_JSON).content(demoRequest("")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.datasource.declaredInReport").value("opd"))
                .andExpect(jsonPath("$.datasource.resolved").value("opd"))
                .andExpect(jsonPath("$.parameters[?(@.name=='hn')].class").value("java.lang.String"))
                .andExpect(jsonPath("$.fonts.used[0]").value("TH Sarabun New"))
                .andExpect(jsonPath("$.fonts.missing").isEmpty())
                .andExpect(jsonPath("$.subreports[0]").value("sub_info.jrxml"))
                .andExpect(jsonPath("$.locale.declaredInReport").value("th"))
                .andExpect(jsonPath("$.locale.resolved").value("th"))
                .andExpect(jsonPath("$.messages['demo.jrxml'].bundle").value("messages"))
                .andExpect(jsonPath("$.messages['demo.jrxml'].languages[0]").value("(base)"))
                .andExpect(jsonPath("$.messages['demo.jrxml'].languages[1]").value("en"))
                .andExpect(jsonPath("$.warnings").isEmpty());
        // a request locale is reported as the resolved one
        mvc.perform(post("/v1/reports/validate").header("X-API-Key", KEY).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mainReport\":{\"url\":\"demo/demo.jrxml\"},\"locale\":\"en-US\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.locale.resolved").value("en-US"));
    }

    @Test
    void prometheusAndReadinessAreExposed() throws Exception {
        mvc.perform(get("/actuator/prometheus").header("Authorization", "Bearer " + KEY))
                .andExpect(status().isOk());
        mvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk());
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
    }
}
