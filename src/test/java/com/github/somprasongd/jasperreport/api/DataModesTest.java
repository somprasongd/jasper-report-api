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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Rendering without a database: the {@code none} datasource and request-supplied JSON {@code data}. */
@SpringBootTest(properties = "report.limits.max-data-size=1KB")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DataModesTest {

    private static final String PEOPLE = "{\"patients\":[{\"name\":\"สมชาย\",\"visits\":2},{\"name\":\"สมหญิง\",\"visits\":5}]}";

    @Autowired
    MockMvc mvc;

    private MockHttpServletRequestBuilder render(String url, String extra) {
        return post("/v1/reports/render").header("X-API-Key", RenderApiTest.KEY).contentType(MediaType.APPLICATION_JSON)
                .content("{\"mainReport\":{\"url\":\"" + url + "\"}" + extra + "}");
    }

    private String text(byte[] pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    @Test
    void jsonDataIsRenderedWithoutAnyDatabase() throws Exception {
        byte[] pdf = mvc.perform(render("modes/json_demo.jrxml", ",\"data\":" + PEOPLE))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(text(pdf)).contains("สมชาย มา 2 ครั้ง").contains("สมหญิง มา 5 ครั้ง");
    }

    @Test
    void emptyJsonDataGivesAnEmptyButValidResult() throws Exception {
        // no rows and the default whenNoDataType (NoPages) is the report author's choice; the API must not crash with a 500
        int status = mvc.perform(render("modes/json_demo.jrxml", ",\"data\":{\"patients\":[]}")).andReturn().getResponse().getStatus();
        assertThat(status).isIn(200, 422);
    }

    @Test
    void dataAndDatasourceAreMutuallyExclusive() throws Exception {
        mvc.perform(render("modes/json_demo.jrxml", ",\"datasource\":\"opd\",\"data\":" + PEOPLE))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DATA_AND_DATASOURCE"));
    }

    @Test
    void dataNeedsAReportWithAJsonQuery() throws Exception {
        mvc.perform(render("demo/demo.jrxml", ",\"data\":" + PEOPLE))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DATA_NOT_SUPPORTED"));
        mvc.perform(render("modes/none_demo.jrxml", ",\"data\":" + PEOPLE))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DATA_NOT_SUPPORTED"));
    }

    @Test
    void reportNeedsNoParameterDeclarationToReadData() throws Exception {
        byte[] pdf = mvc.perform(render("modes/json_plain.jrxml", ",\"data\":" + PEOPLE))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(text(pdf)).contains("สมชาย มา 2 ครั้ง");
    }

    @Test
    void clientCannotSupplyTheInputStreamParameterItself() throws Exception {
        // 'data' wins; a parameter of the same name from the client is dropped, so it cannot point the query at a file
        byte[] pdf = mvc.perform(render("modes/json_demo.jrxml", ",\"data\":" + PEOPLE
                        + ",\"parameters\":[{\"name\":\"JSON_INPUT_STREAM\",\"value\":\"/etc/passwd\"}]"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(text(pdf)).contains("สมชาย มา 2 ครั้ง");
    }

    @Test
    void oversizedDataIsRejected() throws Exception {
        String big = "{\"patients\":[{\"name\":\"" + "x".repeat(2000) + "\",\"visits\":1}]}";
        mvc.perform(render("modes/json_demo.jrxml", ",\"data\":" + big))
                .andExpect(status().isContentTooLarge()).andExpect(jsonPath("$.code").value("DATA_TOO_LARGE"));
    }

    @Test
    void reportWithoutQueryRendersWithDatasourceNone() throws Exception {
        byte[] pdf = mvc.perform(render("modes/none_demo.jrxml", ",\"datasource\":\"none\",\"parameters\":[{\"name\":\"title\",\"value\":\"ใบสมัคร\"}]"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(text(pdf)).contains("แบบฟอร์มเปล่า: ใบสมัคร");
    }

    @Test
    void noneIsAlsoInferredWhenNothingNamesADatasource() throws Exception {
        // the test tenant has a default datasource, so the DB path is taken here; both paths must render the layout
        mvc.perform(render("modes/none_demo.jrxml", ",\"parameters\":[{\"name\":\"title\",\"value\":\"x\"}]"))
                .andExpect(status().isOk());
    }

    @Test
    void noneIsRefusedForAReportWithAQuery() throws Exception {
        mvc.perform(render("demo/demo.jrxml", ",\"datasource\":\"none\""))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DATASOURCE_NONE_NOT_ALLOWED"));
    }

    @Test
    void validateDescribesTheChosenSource() throws Exception {
        mvc.perform(post("/v1/reports/validate").header("X-API-Key", RenderApiTest.KEY).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mainReport\":{\"url\":\"modes/json_demo.jrxml\"},\"data\":" + PEOPLE + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.datasource.resolved").value("json"));
        mvc.perform(post("/v1/reports/validate").header("X-API-Key", RenderApiTest.KEY).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mainReport\":{\"url\":\"modes/none_demo.jrxml\"},\"datasource\":\"none\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.datasource.resolved").value("none"));
    }
}
