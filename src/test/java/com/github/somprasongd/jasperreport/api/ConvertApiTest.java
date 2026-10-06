package com.github.somprasongd.jasperreport.api;

import com.jayway.jsonpath.JsonPath;
import net.sf.jasperreports.engine.xml.JRXmlLoader;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "report.sources.max-bytes=64KB")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ConvertApiTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;

    private MockHttpServletRequestBuilder convert(String jrxml) throws Exception {
        return post("/v1/reports/convert").header("X-API-Key", RenderApiTest.KEY).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("jrxml", jrxml)));
    }

    private static String read(String path) throws Exception {
        return Files.readString(Path.of("src/test/resources", path), StandardCharsets.UTF_8);
    }

    @Test
    void aJr6ReportComesBackInTheJr7SyntaxAndLoads() throws Exception {
        String body = mvc.perform(convert(read("jrxml6/basic_legacy.jrxml")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.alreadyCurrent").value(false))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        String converted = JsonPath.read(body, "$.jrxml");
        assertThat(converted).contains("<element kind=").doesNotContain("<reportElement").doesNotContain("xmlns=");
        assertThat(JRXmlLoader.load(new ByteArrayInputStream(converted.getBytes(StandardCharsets.UTF_8)))).isNotNull();
        assertThat(JsonPath.<List<String>>read(body, "$.warnings")).isNotNull();
    }

    @Test
    void whatCannotBeConvertedIsReportedNotDropped() throws Exception {
        mvc.perform(convert(read("jrxml6/unsupported.jrxml")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.warnings").isNotEmpty())
                .andExpect(jsonPath("$.jrxml", org.hamcrest.Matchers.containsString("not converted")));
    }

    @Test
    void aReportAlreadyInJr7IsReturnedUnchanged() throws Exception {
        String demo = read("reports/demo/demo.jrxml");
        mvc.perform(convert(demo)).andExpect(status().isOk()).andExpect(jsonPath("$.alreadyCurrent").value(true))
                .andExpect(jsonPath("$.jrxml").value(demo)).andExpect(jsonPath("$.warnings").isEmpty());
    }

    @Test
    void doctypeAndExternalEntitiesAreRefusedWithoutEchoingAnything() throws Exception {
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE r [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
                + "<jasperReport xmlns=\"http://jasperreports.sourceforge.net/jasperreports\" name=\"&x;\"/>";
        String body = mvc.perform(convert(xxe)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CONVERT_FAILED"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).doesNotContain("root:");
    }

    @Test
    void notXmlIsABadRequest() throws Exception {
        mvc.perform(convert("this is not xml")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CONVERT_FAILED"));
    }

    @Test
    void aBlankBodyFailsValidation() throws Exception {
        mvc.perform(post("/v1/reports/convert").header("X-API-Key", RenderApiTest.KEY).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jrxml\":\"  \"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void oversizedInputIsRefused() throws Exception {
        mvc.perform(convert("<a>" + "x".repeat(70_000) + "</a>"))
                .andExpect(status().isContentTooLarge()).andExpect(jsonPath("$.code").value("JRXML_TOO_LARGE"));
    }

    @Test
    void anApiKeyIsRequired() throws Exception {
        mvc.perform(post("/v1/reports/convert").contentType(MediaType.APPLICATION_JSON).content("{\"jrxml\":\"x\"}"))
                .andExpect(status().isUnauthorized());
    }
}
