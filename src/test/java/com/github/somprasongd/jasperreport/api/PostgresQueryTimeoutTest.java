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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real PostgreSQL in a container (skipped when Docker is not available): queries work and a runaway one is cancelled. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = "report.limits.query-timeout=2s")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PostgresQueryTimeoutTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("tenants.default.datasources.pg.url", POSTGRES::getJdbcUrl);
        registry.add("tenants.default.datasources.pg.username", POSTGRES::getUsername);
        registry.add("tenants.default.datasources.pg.password", POSTGRES::getPassword);
    }

    @Autowired
    MockMvc mvc;

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder render(String url) {
        return post("/v1/reports/render").header("X-API-Key", RenderApiTest.KEY).contentType(MediaType.APPLICATION_JSON)
                .content("{\"mainReport\":{\"url\":\"" + url + "\"}}");
    }

    @Test
    void aNormalQueryRenders() throws Exception {
        byte[] pdf = mvc.perform(render("limits/pg_ok.jrxml")).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertThat(new PDFTextStripper().getText(doc)).contains("สวัสดี postgres");
        }
    }

    @Test
    void aRunawayQueryIsCancelledAfterTheQueryTimeout() throws Exception {
        long start = System.nanoTime();
        mvc.perform(render("limits/pg_sleep.jrxml")).andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.code").value("QUERY_TIMEOUT"));
        assertThat((System.nanoTime() - start) / 1_000_000_000).isLessThan(20); // pg_sleep(60) was stopped, not waited out
    }
}
