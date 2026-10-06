package com.github.somprasongd.jasperreport.api;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Only 2 pages stay on the heap; the rest of a 20-page report must go through the swap file and come back intact. */
@SpringBootTest(properties = {
        "report.virtualizer.max-pages-in-memory=2",
        "report.virtualizer.directory=target/test-swap",
        "report.limits.max-pages=100"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class VirtualizerTest {

    private static final Path SWAP = Path.of("target/test-swap");

    @Autowired
    MockMvc mvc;

    private MvcResult render(String report, String parameters, String format) throws Exception {
        return mvc.perform(post("/v1/reports/render").header("X-API-Key", RenderApiTest.KEY).contentType(MediaType.APPLICATION_JSON)
                .content("{\"mainReport\":{\"url\":\"" + report + "\"},\"format\":\"" + format + "\",\"parameters\":" + parameters + "}")).andReturn();
    }

    private static long swapFiles() throws Exception {
        if (!Files.isDirectory(SWAP)) {
            return 0;
        }
        try (Stream<Path> files = Files.list(SWAP)) {
            return files.filter(Files::isRegularFile).count();
        }
    }

    /** Watches the swap folder while {@code work} runs and reports the biggest swap file it saw. */
    private static long largestSwapDuring(ThrowingRunnable work) throws Exception {
        AtomicLong largest = new AtomicLong();
        AtomicBoolean running = new AtomicBoolean(true);
        Thread watcher = Thread.ofPlatform().daemon().start(() -> {
            while (running.get()) {
                try (Stream<Path> files = Files.isDirectory(SWAP) ? Files.list(SWAP) : Stream.<Path>empty()) {
                    files.forEach(f -> largest.accumulateAndGet(f.toFile().length(), Math::max));
                } catch (Exception ignored) {
                    // the file may vanish between listing and reading
                }
                try {
                    Thread.sleep(2);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
        try {
            work.run();
        } finally {
            running.set(false);
            watcher.join();
        }
        return largest.get();
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    @Test
    void pagesBeyondTheInMemoryLimitAreSwappedOutAndTheOutputIsIntact() throws Exception {
        MvcResult[] result = new MvcResult[1];
        long swapped = largestSwapDuring(() -> result[0] = render("limits/paged_rows.jrxml", "[{\"name\":\"rows\",\"value\":\"600\"}]", "pdf"));

        assertThat(result[0].getResponse().getStatus()).as(result[0].getResponse().getContentAsString()).isEqualTo(200);
        assertThat(swapped).as("bytes written to the swap file while filling").isPositive();
        try (PDDocument doc = Loader.loadPDF(result[0].getResponse().getContentAsByteArray())) {
            assertThat(doc.getNumberOfPages()).isGreaterThan(10);
            String text = new PDFTextStripper().getText(doc);
            assertThat(text).contains("แถวที่ 1\n").contains("แถวที่ 300\n").contains("แถวที่ 600");
        }
        assertThat(swapFiles()).as("swap file removed after the render").isZero();
    }

    @Test
    void spreadsheetsComeThroughTheSwapFileToo() throws Exception {
        MvcResult result = render("limits/paged_rows.jrxml", "[{\"name\":\"rows\",\"value\":\"600\"}]", "csv");
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8))
                .contains("แถวที่ 1\r\n").contains("แถวที่ 600");
        assertThat(swapFiles()).isZero();
    }

    @Test
    void aRenderThatFailsHalfWayStillRemovesItsSwapFile() throws Exception {
        // 3000 rows = about 150 pages: stops at the 100 page limit after pages have been swapped out
        mvc.perform(post("/v1/reports/render").header("X-API-Key", RenderApiTest.KEY).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mainReport\":{\"url\":\"limits/many_rows.jrxml\"}}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("PAGE_LIMIT_EXCEEDED"));
        assertThat(swapFiles()).isZero();

        mvc.perform(post("/v1/reports/render").header("X-API-Key", RenderApiTest.KEY).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mainReport\":{\"url\":\"limits/missing.jrxml\"}}"))
                .andExpect(status().isNotFound());
        assertThat(swapFiles()).isZero();
    }
}
