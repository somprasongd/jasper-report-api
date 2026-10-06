package com.github.somprasongd.jasperreport.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "report.datasource.allow-request-override=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DatasourceOverrideTest {

    @Autowired
    MockMvc mvc;

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder render(String extra) {
        return post("/v1/reports/render").header("X-API-Key", RenderApiTest.KEY).contentType(MediaType.APPLICATION_JSON)
                .content("{\"mainReport\":{\"url\":\"demo/demo.jrxml\"},\"parameters\":[{\"name\":\"hn\",\"value\":\"HN001\"}]" + extra + "}");
    }

    @Test
    void mismatchingRequestDatasourceIsDeniedWhenOverrideIsOff() throws Exception {
        mvc.perform(render(",\"datasource\":\"ipd\"")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DATASOURCE_OVERRIDE_DENIED"));
        mvc.perform(render(",\"datasource\":\"opd\"")).andExpect(status().isOk());
        mvc.perform(render("")).andExpect(status().isOk());
    }
}
