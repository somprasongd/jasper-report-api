package com.github.somprasongd.jasperreport.api;

import com.sun.net.httpserver.HttpServer;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A report served over http(s): main + listed sub-reports + the .properties files next to each JRXML. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HttpSourceTest {

    static final Map<String, byte[]> CONTENT = new ConcurrentHashMap<>();
    /** When non-zero every request is answered with this status (simulates an outage). */
    static volatile int forcedStatus = 0;
    static volatile long delayMillis = 0;
    static final AtomicInteger INFLIGHT = new AtomicInteger();
    static final AtomicInteger MAX_INFLIGHT = new AtomicInteger();
    /** Bundle downloads (everything but the logo): how many, and when the first started and the last finished. */
    static final AtomicInteger DOWNLOADS = new AtomicInteger();
    static final AtomicLong FIRST_START = new AtomicLong(Long.MAX_VALUE);
    static final AtomicLong LAST_END = new AtomicLong(0);
    static final HttpServer SERVER = start();
    static final String BASE = "http://127.0.0.1:" + SERVER.getAddress().getPort();

    static HttpServer start() {
        try {
            Path dir = Path.of("src/test/resources/reports");
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                boolean download = !path.endsWith(".png");
                int now = INFLIGHT.incrementAndGet();
                MAX_INFLIGHT.accumulateAndGet(now, Math::max);
                if (download) {
                    DOWNLOADS.incrementAndGet();
                    FIRST_START.accumulateAndGet(System.nanoTime(), Math::min);
                }
                try {
                    if (delayMillis > 0) {
                        Thread.sleep(delayMillis);
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    INFLIGHT.decrementAndGet();
                    if (download) {
                        LAST_END.accumulateAndGet(System.nanoTime(), Math::max);
                    }
                }
                // the logo is fetched by JasperReports at render time, not by the bundle download: keep it up during the "outage"
                if (forcedStatus != 0 && !path.endsWith(".png")) {
                    exchange.sendResponseHeaders(forcedStatus, -1);
                    exchange.close();
                    return;
                }
                byte[] body = CONTENT.get(path);
                if (body == null && path.startsWith("/reports/")) {
                    Path file = dir.resolve(path.substring("/reports/".length())).normalize();
                    if (file.startsWith(dir) && Files.isRegularFile(file)) {
                        body = Files.readAllBytes(file);
                        if (path.endsWith("/demo.jrxml")) {
                            // the logo is not part of an http bundle: point the image at an absolute URL, which JasperReports loads itself
                            String text = new String(body, StandardCharsets.UTF_8).replace("$P{IMAGE_DIR} + \"logo.png\"",
                                    "\"" + BASE() + "/reports/demo/assets/logo.png\"");
                            body = text.getBytes(StandardCharsets.UTF_8);
                        }
                    }
                }
                exchange.sendResponseHeaders(body == null ? 404 : 200, body == null ? -1 : body.length);
                if (body != null) {
                    exchange.getResponseBody().write(body);
                }
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static String BASE() {
        return "http://127.0.0.1:" + SERVER.getAddress().getPort();
    }

    @AfterAll
    static void stop() {
        SERVER.stop(0);
    }

    @DynamicPropertySource
    static void http(DynamicPropertyRegistry registry) {
        registry.add("report.sources.http.allowed-hosts", () -> "127.0.0.1");
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    io.micrometer.core.instrument.MeterRegistry meters;

    private static String body(String locale, boolean withSub) {
        return "{\"mainReport\":{\"url\":\"" + BASE + "/reports/demo/demo.jrxml\"},"
                + (withSub ? "\"subReports\":[{\"name\":\"sub_info\",\"url\":\"" + BASE + "/reports/demo/sub_info.jrxml\"}]," : "")
                + "\"parameters\":[{\"name\":\"hn\",\"value\":\"HN001\"},{\"name\":\"print_time\",\"value\":\"2026-10-06T10:30:00+07:00\"}]"
                + (locale == null ? "" : ",\"locale\":\"" + locale + "\"") + "}";
    }

    private MvcResult render(String body) throws Exception {
        return mvc.perform(post("/v1/reports/render").header("X-API-Key", RenderApiTest.KEY)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    private static String text(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        try (PDDocument doc = Loader.loadPDF(result.getResponse().getContentAsByteArray())) {
            return new PDFTextStripper().getText(doc);
        }
    }

    @Test
    void rendersMainSubreportAndMessageBundlesDownloadedOverHttp() throws Exception {
        MvcResult thai = render(body(null, true));
        assertThat(text(thai)).contains("ใบรับรองแพทย์ทดสอบ").contains("ผู้ป่วย: สมชาย ใจดี").contains("จำนวนครั้งที่มารับบริการ: 2");

        MvcResult english = render(body("en", true));
        assertThat(text(english)).contains("Test medical certificate").contains("Patient: Somchai Jaidee")
                .contains("Number of visits: 2").contains("Printed on 6 October 2026 10:30");
        assertThat(english.getResponse().getHeader("Content-Language")).isEqualTo("en");

        // an image given as an absolute URL in the JRXML is fetched by JasperReports itself
        try (PDDocument doc = Loader.loadPDF(english.getResponse().getContentAsByteArray())) {
            BufferedImage page = new PDFRenderer(doc).renderImageWithDPI(0, 72);
            int rgb = page.getRGB(490, 30);
            assertThat((rgb >> 16) & 0xff).as("red logo pixel").isGreaterThan(150);
            assertThat((rgb >> 8) & 0xff).isLessThan(100);
        }
    }

    @Test
    void editsToTheMessageFilesAreSeenOnTheNextRequest() throws Exception {
        MvcResult before = render(body("en", true));
        assertThat(text(before)).contains("Test medical certificate");
        String version = before.getResponse().getHeader("X-Report-Version");
        try {
            CONTENT.put("/reports/demo/messages_en.properties", "title=Edited over http\npatient=Patient\nprinted_at=Printed on\n".getBytes(StandardCharsets.UTF_8));
            MvcResult after = render(body("en", true));
            assertThat(text(after)).contains("Edited over http");
            assertThat(after.getResponse().getHeader("X-Report-Version")).isNotEqualTo(version);
        } finally {
            CONTENT.remove("/reports/demo/messages_en.properties");
        }
    }

    @Test
    void subreportDirModeWorksOverHttpToo() throws Exception {
        MvcResult result = render("{\"mainReport\":{\"url\":\"" + BASE + "/reports/subdirmode/main_dir.jrxml\"},"
                + "\"subReports\":[{\"url\":\"" + BASE + "/reports/subdirmode/sub_dir.jrxml\"}],"
                + "\"parameters\":[{\"name\":\"hn\",\"value\":\"HN001\"}]}");
        assertThat(text(result)).contains("แบบ SUBREPORT_DIR: สมชาย ใจดี").contains("sub-report (.jasper): visits=2");
    }

    @Test
    void anOutageOfTheStorageKeepsServingTheLastGoodVersion() throws Exception {
        MvcResult good = render(body("en", true));
        assertThat(text(good)).contains("Test medical certificate");
        String version = good.getResponse().getHeader("X-Report-Version");
        double staleBefore = meters.counter("report.source.stale", "source", "http").count();
        try {
            forcedStatus = 500;
            MvcResult duringOutage = render(body("en", true));
            assertThat(text(duringOutage)).contains("Test medical certificate");
            assertThat(duringOutage.getResponse().getHeader("X-Report-Version")).isEqualTo(version);
            assertThat(meters.counter("report.source.stale", "source", "http").count()).isGreaterThan(staleBefore);

            // never loaded before: nothing to fall back to
            mvc.perform(post("/v1/reports/render").header("X-API-Key", RenderApiTest.KEY).contentType(MediaType.APPLICATION_JSON)
                            .content(body("ja", true)))
                    .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("STORAGE_ERROR"));

            // an expired / forbidden URL is an answer, not an outage: serving the stale copy would outlive the access
            forcedStatus = 403;
            mvc.perform(post("/v1/reports/render").header("X-API-Key", RenderApiTest.KEY).contentType(MediaType.APPLICATION_JSON)
                            .content(body("en", true)))
                    .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("STORAGE_ERROR"));

            // "not found" is an answer about the report, not an outage: it must not be masked
            forcedStatus = 404;
            mvc.perform(post("/v1/reports/render").header("X-API-Key", RenderApiTest.KEY).contentType(MediaType.APPLICATION_JSON)
                            .content(body("en", true)))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("REPORT_NOT_FOUND"));
        } finally {
            forcedStatus = 0;
        }
        CONTENT.put("/reports/demo/messages_en.properties", "title=Back after the outage\npatient=P\nprinted_at=Q\n".getBytes(StandardCharsets.UTF_8));
        try {
            assertThat(text(render(body("en", true)))).contains("Back after the outage");
        } finally {
            CONTENT.remove("/reports/demo/messages_en.properties");
        }
    }

    @Test
    void theFilesOfOneReportAreDownloadedInParallel() throws Exception {
        delayMillis = 150;
        MAX_INFLIGHT.set(0);
        DOWNLOADS.set(0);
        FIRST_START.set(Long.MAX_VALUE);
        LAST_END.set(0);
        try {
            // a language no other test used: a cold load of main + sub-report + the possible message files
            MvcResult result = render(body("ko", true));
            assertThat(text(result)).contains("ใบรับรองแพทย์ทดสอบ");
            assertThat(MAX_INFLIGHT.get()).as("downloads in flight at once").isGreaterThanOrEqualTo(4);
            // only the download phase is timed (as the server saw it), not compiling or rendering, which depend on the machine's load
            long downloadMillis = (LAST_END.get() - FIRST_START.get()) / 1_000_000;
            long sequentialMillis = DOWNLOADS.get() * delayMillis;
            assertThat(downloadMillis).as("%d downloads of %d ms would take %d ms one after the other", DOWNLOADS.get(), delayMillis, sequentialMillis)
                    .isLessThan(sequentialMillis * 3 / 4);
        } finally {
            delayMillis = 0;
        }
    }

    @Test
    void aLanguageWithoutAFileFallsBackToTheBaseFile() throws Exception {
        assertThat(text(render(body("fr", true)))).contains("ใบรับรองแพทย์ทดสอบ");
    }

    @Test
    void aSubreportThatIsNotListedGivesAHelpfulError() throws Exception {
        mvc.perform(post("/v1/reports/render").header("X-API-Key", RenderApiTest.KEY).contentType(MediaType.APPLICATION_JSON).content(body(null, false)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REPORT_NOT_FOUND"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("subReports[]")));
    }

    @Test
    void everyUrlMustBeOnAnAllowedHost() throws Exception {
        String other = BASE.replace("127.0.0.1", "localhost");
        mvc.perform(post("/v1/reports/render").header("X-API-Key", RenderApiTest.KEY).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mainReport\":{\"url\":\"" + BASE + "/reports/demo/demo.jrxml\"},\"subReports\":[{\"name\":\"sub_info\",\"url\":\"" + other + "/reports/demo/sub_info.jrxml\"}]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SOURCE_NOT_ALLOWED"));
        mvc.perform(post("/v1/reports/render").header("X-API-Key", RenderApiTest.KEY).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mainReport\":{\"url\":\"" + BASE + "/reports/demo/demo.jrxml\"},\"subReports\":[{\"name\":\"sub_info\",\"url\":\"s3://reports/x/y.jrxml\"}]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SOURCE_NOT_ALLOWED"));
        mvc.perform(post("/v1/reports/render").header("X-API-Key", RenderApiTest.KEY).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mainReport\":{\"url\":\"" + BASE + "/reports/demo/nothing.jrxml\"}}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("REPORT_NOT_FOUND"));
    }
}
