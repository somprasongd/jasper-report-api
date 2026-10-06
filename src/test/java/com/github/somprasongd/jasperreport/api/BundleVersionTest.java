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
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Editing a JRXML on the mounted folder is picked up by the next request without restart or redeploy. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BundleVersionTest {

    static final Path ROOT = createRoot();

    static Path createRoot() {
        try {
            Path root = Files.createTempDirectory("reports-root");
            Path dir = Files.createDirectories(root.resolve("live"));
            for (String f : new String[]{"demo.jrxml", "sub_info.jrxml", "messages.properties", "messages_en.properties",
                    "sub_messages.properties", "sub_messages_en.properties"}) {
                Files.copy(Path.of("src/test/resources/reports/demo/" + f), dir.resolve(f), StandardCopyOption.REPLACE_EXISTING);
            }
            Files.createDirectories(dir.resolve("assets"));
            Files.copy(Path.of("src/test/resources/reports/demo/assets/logo.png"), dir.resolve("assets/logo.png"));
            return root;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void root(DynamicPropertyRegistry registry) {
        registry.add("report.sources.local.root", ROOT::toString);
    }

    @Autowired
    MockMvc mvc;

    private MvcResult render() throws Exception {
        return mvc.perform(post("/v1/reports/render").header("X-API-Key", RenderApiTest.KEY).contentType(MediaType.APPLICATION_JSON)
                .content("{\"mainReport\":{\"url\":\"live/demo.jrxml\"},\"parameters\":[{\"name\":\"hn\",\"value\":\"HN001\"}]}")).andReturn();
    }

    private static String text(MvcResult result) throws Exception {
        try (PDDocument doc = Loader.loadPDF(result.getResponse().getContentAsByteArray())) {
            return new PDFTextStripper().getText(doc);
        }
    }

    @Test
    void editedFileIsRecompiledOnTheNextRequest() throws Exception {
        MvcResult before = render();
        assertThat(text(before)).contains("ใบรับรองแพทย์ทดสอบ");
        String v1 = before.getResponse().getHeader("X-Report-Version");
        assertThat(render().getResponse().getHeader("X-Report-Version")).as("unchanged folder, same version").isEqualTo(v1);

        // the title lives in the message bundle: editing a .properties file is picked up like editing the JRXML
        Path messages = ROOT.resolve("live/messages.properties");
        Files.writeString(messages, Files.readString(messages).replace("ใบรับรองแพทย์ทดสอบ", "แก้ไขบนโฟลเดอร์แล้ว"), StandardCharsets.UTF_8);
        Files.setLastModifiedTime(messages, FileTime.fromMillis(System.currentTimeMillis() + 5000));

        MvcResult after = render();
        assertThat(text(after)).contains("แก้ไขบนโฟลเดอร์แล้ว");
        assertThat(after.getResponse().getHeader("X-Report-Version")).isNotEqualTo(v1);
    }

    @Test
    void aBrokenEditGivesACompileErrorInsteadOfServingTheOldReport() throws Exception {
        Path broken = ROOT.resolve("live/sub_info.jrxml");
        String original = Files.readString(broken);
        try {
            Files.writeString(broken, original.replace("</jasperReport>", ""), StandardCharsets.UTF_8);
            Files.setLastModifiedTime(broken, FileTime.fromMillis(System.currentTimeMillis() + 9000));
            MvcResult result = render();
            assertThat(result.getResponse().getStatus()).isGreaterThanOrEqualTo(400);
        } finally {
            Files.writeString(broken, original, StandardCharsets.UTF_8);
            Files.setLastModifiedTime(broken, FileTime.fromMillis(System.currentTimeMillis() + 12000));
        }
    }

    @Test
    void validateWarnsAboutMessageKeysMissingInALanguage() throws Exception {
        Path english = ROOT.resolve("live/messages_en.properties");
        String original = Files.readString(english);
        try {
            Files.writeString(english, "title=Only a title\n", StandardCharsets.UTF_8);
            Files.setLastModifiedTime(english, FileTime.fromMillis(System.currentTimeMillis() + 20000));
            String body = mvc.perform(post("/v1/reports/validate").header("X-API-Key", RenderApiTest.KEY).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"mainReport\":{\"url\":\"live/demo.jrxml\"}}"))
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(body).contains("messages_en.properties lacks keys").contains("patient").contains("printed_at");
        } finally {
            Files.writeString(english, original, StandardCharsets.UTF_8);
            Files.setLastModifiedTime(english, FileTime.fromMillis(System.currentTimeMillis() + 25000));
        }
    }
}
