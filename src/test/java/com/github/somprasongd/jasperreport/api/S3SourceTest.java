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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Runs against a real S3-compatible store: rustfs in a container (skipped when Docker is not available). */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class S3SourceTest {

    static final String ACCESS_KEY = "testkey";
    static final String SECRET_KEY = "testsecret123";

    @Container
    static final GenericContainer<?> RUSTFS = new GenericContainer<>("rustfs/rustfs:latest")
            .withEnv("RUSTFS_ACCESS_KEY", ACCESS_KEY)
            .withEnv("RUSTFS_SECRET_KEY", SECRET_KEY)
            .withCommand("/data")
            .withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/health").forPort(9000).forStatusCode(200));

    @DynamicPropertySource
    static void s3(DynamicPropertyRegistry registry) {
        registry.add("report.sources.s3.endpoint", () -> endpoint());
        registry.add("report.sources.s3.access-key", () -> ACCESS_KEY);
        registry.add("report.sources.s3.secret-key", () -> SECRET_KEY);
        registry.add("report.sources.s3.allowed-buckets", () -> "reports");
    }

    static String endpoint() {
        return "http://" + RUSTFS.getHost() + ":" + RUSTFS.getMappedPort(9000);
    }

    @Autowired
    MockMvc mvc;

    static S3Client admin() {
        return S3Client.builder()
                .endpointOverride(URI.create(endpoint()))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
    }

    private static void put(S3Client s3, String key, byte[] content) {
        s3.putObject(PutObjectRequest.builder().bucket("reports").key(key).build(), RequestBody.fromBytes(content));
    }

    private static byte[] resource(String path) throws Exception {
        return Files.readAllBytes(Path.of("src/test/resources/reports", path));
    }

    private MvcResult render(String url) throws Exception {
        return mvc.perform(post("/v1/reports/render").header("X-API-Key", RenderApiTest.KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"mainReport\":{\"url\":\"" + url + "\"},\"parameters\":[{\"name\":\"hn\",\"value\":\"HN001\"}]}")).andReturn();
    }

    private static String text(MvcResult result) throws Exception {
        try (PDDocument doc = Loader.loadPDF(result.getResponse().getContentAsByteArray())) {
            return new PDFTextStripper().getText(doc);
        }
    }

    @Test
    void rendersFromS3FolderPicksUpChangesAndEnforcesTheAllowList() throws Exception {
        try (S3Client s3 = admin()) {
            s3.createBucket(CreateBucketRequest.builder().bucket("reports").build());
            put(s3, "opd/demo/th_demo.jrxml", resource("demo/th_demo.jrxml"));
            put(s3, "opd/demo/sub_info.jrxml", resource("demo/sub_info.jrxml"));
            put(s3, "opd/demo/assets/logo.png", resource("demo/assets/logo.png"));

            MvcResult first = render("s3://reports/opd/demo/th_demo.jrxml");
            assertThat(first.getResponse().getStatus()).as(first.getResponse().getContentAsString()).isEqualTo(200);
            String firstText = text(first);
            assertThat(firstText).contains("ใบรับรองแพทย์ทดสอบ").contains("จำนวนครั้งที่มารับบริการ: 2");
            String version1 = first.getResponse().getHeader("X-Report-Version");

            // same objects -> same version (no re-download, no recompile)
            assertThat(render("s3://reports/opd/demo/th_demo.jrxml").getResponse().getHeader("X-Report-Version")).isEqualTo(version1);

            // edit the report in storage -> next render uses it
            String edited = new String(resource("demo/th_demo.jrxml"), java.nio.charset.StandardCharsets.UTF_8)
                    .replace("ใบรับรองแพทย์ทดสอบ", "ฉบับแก้ไขใน S3");
            put(s3, "opd/demo/th_demo.jrxml", edited.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            MvcResult second = render("s3://reports/opd/demo/th_demo.jrxml");
            assertThat(text(second)).contains("ฉบับแก้ไขใน S3").doesNotContain("ใบรับรองแพทย์ทดสอบ");
            assertThat(second.getResponse().getHeader("X-Report-Version")).isNotEqualTo(version1);
        }

        mvc.perform(post("/v1/reports/render").header("X-API-Key", RenderApiTest.KEY).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mainReport\":{\"url\":\"s3://reports/opd/demo/missing.jrxml\"}}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("REPORT_NOT_FOUND"));
        mvc.perform(post("/v1/reports/render").header("X-API-Key", RenderApiTest.KEY).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mainReport\":{\"url\":\"s3://reports/opd/../secret/x.jrxml\"}}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SOURCE_NOT_ALLOWED"));
        mvc.perform(post("/v1/reports/render").header("X-API-Key", RenderApiTest.KEY).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mainReport\":{\"url\":\"s3://reports/root.jrxml\"}}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SOURCE_NOT_ALLOWED"));
        mvc.perform(post("/v1/reports/render").header("X-API-Key", RenderApiTest.KEY).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mainReport\":{\"url\":\"s3://private-bucket/a/b.jrxml\"}}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SOURCE_NOT_ALLOWED"));

        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
    }
}
