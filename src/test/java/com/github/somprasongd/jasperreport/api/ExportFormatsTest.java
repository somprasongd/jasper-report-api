package com.github.somprasongd.jasperreport.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code format: xlsx} and {@code format: csv}, fed from request JSON so no database is involved. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ExportFormatsTest {

    private static final String PEOPLE = "{\"patients\":[{\"name\":\"สมชาย\",\"visits\":2},{\"name\":\"สมหญิง\",\"visits\":5}]}";

    @Autowired
    MockMvc mvc;

    private MockHttpServletRequestBuilder render(String url, String extra) {
        return post("/v1/reports/render").header("X-API-Key", RenderApiTest.KEY).contentType(MediaType.APPLICATION_JSON)
                .content("{\"mainReport\":{\"url\":\"" + url + "\"}" + extra + "}");
    }

    private MockHttpServletResponse people(String extra) throws Exception {
        return mvc.perform(render("modes/json_demo.jrxml", ",\"data\":" + PEOPLE + extra))
                .andExpect(status().isOk()).andReturn().getResponse();
    }

    /** All the text of an .xlsx, whichever of shared strings or inline strings the exporter wrote it in. */
    private static String xlsxText(byte[] xlsx) throws Exception {
        StringBuilder text = new StringBuilder();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(xlsx))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.getName().startsWith("xl/") && entry.getName().endsWith(".xml")) {
                    text.append(new String(zip.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        }
        return text.toString();
    }

    private static java.util.List<String> xlsxEntries(byte[] xlsx) throws Exception {
        java.util.List<String> names = new java.util.ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(xlsx))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                names.add(entry.getName());
            }
        }
        return names;
    }

    @Test
    void xlsxIsARealWorkbookWithTheReportText() throws Exception {
        MockHttpServletResponse response = people(",\"format\":\"xlsx\"");

        assertThat(response.getContentType()).isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        assertThat(response.getContentAsByteArray()).startsWith('P', 'K');
        assertThat(response.getHeader(HttpHeaders.CONTENT_DISPOSITION)).startsWith("attachment").contains("json_demo.xlsx");
        String text = xlsxText(response.getContentAsByteArray());
        assertThat(text).contains("สมชาย มา 2 ครั้ง").contains("สมหญิง มา 5 ครั้ง");
    }

    @Test
    void xlsxFromADatabaseReportWithImagesAndBarcodes() throws Exception {
        // demo.jrxml has a logo, a QR code, a Code128 and a sub-report: all must survive the spreadsheet exporter
        MockHttpServletResponse response = mvc.perform(render("demo/demo.jrxml",
                        ",\"format\":\"XLSX\",\"parameters\":[{\"name\":\"hn\",\"value\":\"HN001\"}]"))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(xlsxText(response.getContentAsByteArray())).contains("สมชาย ใจดี");
        assertThat(xlsxEntries(response.getContentAsByteArray())).as("logo and codes are embedded as pictures").anyMatch(n -> n.startsWith("xl/media/"));
    }

    @Test
    void csvStartsWithABomSoExcelReadsThai() throws Exception {
        MockHttpServletResponse response = people(",\"format\":\"csv\"");

        assertThat(response.getContentType()).isEqualTo("text/csv;charset=UTF-8");
        assertThat(response.getHeader(HttpHeaders.CONTENT_DISPOSITION)).startsWith("attachment").contains("json_demo.csv");
        byte[] bytes = response.getContentAsByteArray();
        assertThat(bytes).startsWith((byte) 0xEF, (byte) 0xBB, (byte) 0xBF);
        String text = new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        assertThat(text).contains("สมชาย มา 2 ครั้ง").contains("สมหญิง มา 5 ครั้ง").contains("\r\n");
    }

    @Test
    void fileNameGetsTheExtensionOfTheFormat() throws Exception {
        assertThat(people(",\"format\":\"xlsx\",\"fileName\":\"สรุปผู้ป่วย\"").getHeader(HttpHeaders.CONTENT_DISPOSITION))
                .contains("filename*=UTF-8''" + java.net.URLEncoder.encode("สรุปผู้ป่วย.xlsx", StandardCharsets.UTF_8).replace("+", "%20"));
        assertThat(people(",\"format\":\"csv\",\"fileName\":\"out.CSV\"").getHeader(HttpHeaders.CONTENT_DISPOSITION))
                .contains("out.CSV").doesNotContain("out.CSV.csv");
    }

    @Test
    void pdfIsStillTheDefaultAndStaysInline() throws Exception {
        MockHttpServletResponse response = people("");
        assertThat(response.getContentType()).isEqualTo("application/pdf");
        assertThat(response.getHeader(HttpHeaders.CONTENT_DISPOSITION)).startsWith("inline").contains("json_demo.pdf");
        assertThat(response.getContentAsByteArray()).startsWith("%PDF".getBytes(StandardCharsets.US_ASCII));
    }

    @Test
    void dispositionOverridesTheDefaultOfTheFormat() throws Exception {
        MockHttpServletResponse download = people(",\"disposition\":\"attachment\"");
        assertThat(download.getContentType()).isEqualTo("application/pdf");
        assertThat(download.getHeader(HttpHeaders.CONTENT_DISPOSITION)).startsWith("attachment").contains("json_demo.pdf");

        MockHttpServletResponse show = people(",\"format\":\"csv\",\"disposition\":\"INLINE\"");
        assertThat(show.getContentType()).startsWith("text/csv");
        assertThat(show.getHeader(HttpHeaders.CONTENT_DISPOSITION)).startsWith("inline").contains("json_demo.csv");

        assertThat(people(",\"disposition\":\"\"").getHeader(HttpHeaders.CONTENT_DISPOSITION)).startsWith("inline");
    }

    @Test
    void unknownDispositionIsRefused() throws Exception {
        mvc.perform(render("modes/json_demo.jrxml", ",\"data\":" + PEOPLE + ",\"disposition\":\"download\""))
                .andExpect(status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("DISPOSITION_INVALID"));
    }
}
