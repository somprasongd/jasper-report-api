package com.github.somprasongd.jasperreport.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code report.limits.query-timeout} is applied through JDBC, so it works for every driver (here H2). */
@SpringBootTest(properties = "report.limits.query-timeout=1s")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class QueryTimeoutTest {

    @Autowired
    MockMvc mvc;

    @Test
    void aRunawayQueryIsCancelledInTheDatabase() throws Exception {
        long start = System.nanoTime();
        mvc.perform(post("/v1/reports/render").header("X-API-Key", RenderApiTest.KEY).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mainReport\":{\"url\":\"limits/slow_query.jrxml\"}}"))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.code").value("QUERY_TIMEOUT"));
        assertThat((System.nanoTime() - start) / 1_000_000_000).isLessThan(20);
    }
}
