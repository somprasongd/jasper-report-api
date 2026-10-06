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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A sub-report fed from a nested array of the request {@code data}, via {@code JsonDataSource.subDataSource(...)}. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JsonSubreportTest {

    private static final String DATA = """
            {"patients":[
              {"name":"สมชาย","visits":[{"date":"2026-01-05","dept":"อายุรกรรม"},{"date":"2026-02-10","dept":"ศัลยกรรม"}]},
              {"name":"สมหญิง","visits":[]},
              {"name":"มานี","visits":[{"date":"2026-03-01","dept":"กุมารเวช"}]}
            ]}""";

    @Autowired
    MockMvc mvc;

    private String render(String data) throws Exception {
        byte[] pdf = mvc.perform(post("/v1/reports/render").header("X-API-Key", RenderApiTest.KEY).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mainReport\":{\"url\":\"modes/json_master.jrxml\"},\"data\":" + data + "}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    @Test
    void eachPatientGetsItsOwnVisitsFromTheSameJson() throws Exception {
        String text = render(DATA);

        assertThat(text).contains("ผู้ป่วย: สมชาย").contains("ผู้ป่วย: สมหญิง").contains("ผู้ป่วย: มานี");
        assertThat(text).contains("- 2026-01-05 อายุรกรรม").contains("- 2026-02-10 ศัลยกรรม").contains("- 2026-03-01 กุมารเวช");
        // the visits are printed under the right patient, not pooled or repeated
        assertThat(text.indexOf("- 2026-01-05")).isBetween(text.indexOf("ผู้ป่วย: สมชาย"), text.indexOf("ผู้ป่วย: สมหญิง"));
        assertThat(text.indexOf("- 2026-03-01")).isGreaterThan(text.indexOf("ผู้ป่วย: มานี"));
        assertThat(text.split("- 2026", -1)).as("one line per visit").hasSize(4);
    }

    @Test
    void aPatientWithoutAVisitsArrayPrintsNoVisits() throws Exception {
        String text = render("{\"patients\":[{\"name\":\"สมศรี\"}]}");
        assertThat(text).contains("ผู้ป่วย: สมศรี").doesNotContain("- 2026");
    }
}
