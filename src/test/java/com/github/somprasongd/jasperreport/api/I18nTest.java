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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The report declares report.locale=th and a message bundle; the request's locale wins when it is sent. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class I18nTest {

    @Autowired
    MockMvc mvc;

    private MvcResult render(String locale) throws Exception {
        String loc = locale == null ? "" : ",\"locale\":\"" + locale + "\"";
        return mvc.perform(post("/v1/reports/render").header("X-API-Key", RenderApiTest.KEY).contentType(MediaType.APPLICATION_JSON)
                .content("{\"mainReport\":{\"url\":\"demo/demo.jrxml\"},\"parameters\":[{\"name\":\"hn\",\"value\":\"HN001\"},"
                        + "{\"name\":\"print_time\",\"value\":\"2026-10-06T10:30:00+07:00\"}]" + loc + "}")).andReturn();
    }

    private static String text(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        try (PDDocument doc = Loader.loadPDF(result.getResponse().getContentAsByteArray())) {
            return new PDFTextStripper().getText(doc);
        }
    }

    @Test
    void withoutALocaleTheReportsOwnDefaultIsUsed() throws Exception {
        MvcResult result = render(null);
        assertThat(text(result)).contains("ใบรับรองแพทย์ทดสอบ").contains("ผู้ป่วย: สมชาย ใจดี")
                .contains("พิมพ์เมื่อ 6 ตุลาคม 2026 10:30").contains("จำนวนครั้งที่มารับบริการ: 2");
        assertThat(result.getResponse().getHeader("Content-Language")).isEqualTo("th");
    }

    @Test
    void theRequestLocaleWinsAndSwitchesTextDatesDataAndTheSubreport() throws Exception {
        MvcResult result = render("en");
        assertThat(text(result)).contains("Test medical certificate").contains("Patient: Somchai Jaidee")   // bundle + translated column
                .contains("Printed on 6 October 2026 10:30")                                                  // locale-aware date
                .contains("Number of visits: 2")                                                              // the sub-report's own bundle
                .doesNotContain("ใบรับรองแพทย์ทดสอบ");
        assertThat(result.getResponse().getHeader("Content-Language")).isEqualTo("en");
    }

    @Test
    void thaiWithCountryPrintsBuddhistEraYearsInDatePatterns() throws Exception {
        // th-TH makes java.text pick the Buddhist calendar; plain "th" keeps Gregorian years
        assertThat(text(render("th-TH"))).contains("พิมพ์เมื่อ 6 ตุลาคม 2569 10:30");
        assertThat(text(render("th"))).contains("พิมพ์เมื่อ 6 ตุลาคม 2026 10:30");
    }

    @Test
    void aLanguageWithoutABundleFallsBackToTheDefaultBundle() throws Exception {
        assertThat(text(render("fr"))).contains("ใบรับรองแพทย์ทดสอบ").contains("ผู้ป่วย: สมชาย ใจดี").contains("6 octobre 2026");
    }

    @Test
    void invalidLanguageTagsAreRejected() throws Exception {
        for (String bad : new String[]{"thai language", "t", "en_US_@", "../x"}) {
            mvc.perform(post("/v1/reports/render").header("X-API-Key", RenderApiTest.KEY).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"mainReport\":{\"url\":\"demo/demo.jrxml\"},\"locale\":\"" + bad + "\"}"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("LOCALE_INVALID"));
        }
    }
}
