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

/** A server with no usable datasource at all can still render {@code none} and JSON reports. */
@SpringBootTest(properties = {"tenants.default.datasources.opd.url=", "tenants.default.datasources.ipd.url="})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NoDatabaseServerTest {

    @Autowired
    MockMvc mvc;

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder render(String url, String extra) {
        return post("/v1/reports/render").header("X-API-Key", RenderApiTest.KEY).contentType(MediaType.APPLICATION_JSON)
                .content("{\"mainReport\":{\"url\":\"" + url + "\"}" + extra + "}");
    }

    @Test
    void noneAndJsonWorkWithoutAnyTenant() throws Exception {
        mvc.perform(render("modes/none_demo.jrxml", ",\"parameters\":[{\"name\":\"title\",\"value\":\"x\"}]")).andExpect(status().isOk());
        mvc.perform(render("modes/json_demo.jrxml", ",\"data\":{\"patients\":[{\"name\":\"A\",\"visits\":1}]}")).andExpect(status().isOk());
    }

    @Test
    void aReportWithAQueryStillSaysTenantUnknown() throws Exception {
        mvc.perform(render("demo/demo.jrxml", ",\"parameters\":[{\"name\":\"hn\",\"value\":\"HN001\"}]"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("TENANT_UNKNOWN"));
    }
}
