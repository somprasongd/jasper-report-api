package com.github.somprasongd.jasperreport.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "report.export.csv-bom=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CsvWithoutBomTest {

    @Autowired
    MockMvc mvc;

    @Test
    void bomCanBeTurnedOff() throws Exception {
        byte[] bytes = mvc.perform(post("/v1/reports/render").header("X-API-Key", RenderApiTest.KEY).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mainReport\":{\"url\":\"modes/json_demo.jrxml\"},\"format\":\"csv\","
                                + "\"data\":{\"patients\":[{\"name\":\"สมชาย\",\"visits\":2}]}}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(bytes[0]).isNotEqualTo((byte) 0xEF);
        assertThat(new String(bytes, StandardCharsets.UTF_8)).startsWith("สมชาย มา 2 ครั้ง");
    }
}
