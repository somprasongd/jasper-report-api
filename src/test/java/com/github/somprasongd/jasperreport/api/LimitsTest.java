package com.github.somprasongd.jasperreport.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.concurrent.CompletableFuture;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "report.limits.max-pages=3",
        "report.limits.fill-timeout=700ms",
        "report.limits.max-concurrent-renders=1",
        "report.limits.queue-wait=200ms"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LimitsTest {

    @Autowired
    MockMvc mvc;

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder render(String url) {
        return post("/v1/reports/render").header("X-API-Key", RenderApiTest.KEY)
                .contentType(MediaType.APPLICATION_JSON).content("{\"mainReport\":{\"url\":\"" + url + "\"}}");
    }

    @Test
    void reportsOverThePageLimitAreStopped() throws Exception {
        mvc.perform(render("limits/many_rows.jrxml"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PAGE_LIMIT_EXCEEDED"));
    }

    @Test
    void reportsOverTheFillTimeoutAreStopped() throws Exception {
        mvc.perform(render("limits/slow.jrxml"))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.code").value("RENDER_TIMEOUT"));
    }

    @Test
    void whenAllRenderSlotsAreBusyTheCallerGetsRetryAfter() throws Exception {
        CompletableFuture<Void> first = CompletableFuture.runAsync(() -> {
            try {
                mvc.perform(render("limits/slow.jrxml"));
            } catch (Exception ignored) {
                // outcome of the first request is irrelevant here (it times out)
            }
        });
        Thread.sleep(300); // let the first request take the only slot
        mvc.perform(render("limits/slow.jrxml"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.code").value("RENDER_BUSY"));
        first.join();
    }
}
