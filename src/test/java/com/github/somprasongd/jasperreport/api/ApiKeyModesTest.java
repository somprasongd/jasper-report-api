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

@SpringBootTest(properties = "report.security.api-key.mode=optional")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApiKeyModesTest {

    @Autowired
    MockMvc mvc;

    private static final String BODY = "{\"mainReport\":{\"url\":\"demo/demo.jrxml\"},\"parameters\":[{\"name\":\"hn\",\"value\":\"HN001\"}]}";

    @Test
    void optionalModeAcceptsNoKeyButNeverAWrongKey() throws Exception {
        mvc.perform(post("/v1/reports/render").contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isOk());
        mvc.perform(post("/v1/reports/render").header("X-API-Key", RenderApiTest.KEY).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk());
        mvc.perform(post("/v1/reports/render").header("X-API-Key", "jra_wrong").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("API_KEY_INVALID"));
    }
}
