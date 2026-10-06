package com.github.somprasongd.jasperreport.api;

import com.jayway.jsonpath.JsonPath;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeAll;
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
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A private bucket on rustfs, a short-lived presigned GET URL as {@code mainReport.url}. The API has no S3
 * credentials here: it only reaches the store through the http(s) source and the URL it was handed.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PresignedUrlTest {

    static final String ACCESS_KEY = "testkey";
    static final String SECRET_KEY = "testsecret123";
    static final String BUCKET = "patient-documents";
    static final String MAIN = "private/lang_none.jrxml";

    @Container
    static final GenericContainer<?> RUSTFS = new GenericContainer<>("rustfs/rustfs:latest")
            .withEnv("RUSTFS_ACCESS_KEY", ACCESS_KEY)
            .withEnv("RUSTFS_SECRET_KEY", SECRET_KEY)
            .withCommand("/data")
            .withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/health").forPort(9000).forStatusCode(200));

    @DynamicPropertySource
    static void http(DynamicPropertyRegistry registry) {
        registry.add("report.sources.http.allowed-hosts", () -> RUSTFS.getHost());
    }

    static String endpoint() {
        return "http://" + RUSTFS.getHost() + ":" + RUSTFS.getMappedPort(9000);
    }

    @Autowired
    MockMvc mvc;

    private static final StaticCredentialsProvider CREDENTIALS =
            StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY));

    static S3Client admin() {
        return S3Client.builder()
                .endpointOverride(URI.create(endpoint()))
                .region(Region.US_EAST_1)
                .credentialsProvider(CREDENTIALS)
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
    }

    /** What the HIS API would hand to a client: a GET URL for one object that stops working after {@code ttl}. */
    static String presign(String key, Duration ttl) {
        try (S3Presigner presigner = S3Presigner.builder()
                .endpointOverride(URI.create(endpoint()))
                .region(Region.US_EAST_1)
                .credentialsProvider(CREDENTIALS)
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build()) {
            return presigner.presignGetObject(GetObjectPresignRequest.builder()
                    .signatureDuration(ttl)
                    .getObjectRequest(GetObjectRequest.builder().bucket(BUCKET).key(key).build())
                    .build()).url().toString();
        }
    }

    private static void put(String key, byte[] content) {
        try (S3Client s3 = admin()) {
            s3.putObject(PutObjectRequest.builder().bucket(BUCKET).key(key).build(), RequestBody.fromBytes(content));
        }
    }

    private static byte[] resource(String path) throws Exception {
        return Files.readAllBytes(Path.of("src/test/resources/reports", path));
    }

    @BeforeAll
    static void bucket() throws Exception {
        try (S3Client s3 = admin()) {
            s3.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build()); // private: no bucket policy
        }
        put(MAIN, resource("lang/lang_none.jrxml"));
        put("private/main_dir.jrxml", resource("subdirmode/main_dir.jrxml"));
        put("private/sub_dir.jrxml", resource("subdirmode/sub_dir.jrxml"));
    }

    private static String body(String url) {
        return "{\"mainReport\":{\"url\":\"" + url + "\"},\"parameters\":[{\"name\":\"hn\",\"value\":\"HN001\"}]}";
    }

    private MvcResult render(String url) throws Exception {
        return mvc.perform(post("/v1/reports/render").header("X-API-Key", RenderApiTest.KEY)
                .contentType(MediaType.APPLICATION_JSON).content(body(url))).andReturn();
    }

    private static String text(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        try (PDDocument doc = Loader.loadPDF(result.getResponse().getContentAsByteArray())) {
            return new PDFTextStripper().getText(doc);
        }
    }

    private static long cachedFolders() throws Exception {
        Path http = Path.of("target/test-cache/http");
        if (!Files.isDirectory(http)) {
            return 0;
        }
        try (Stream<Path> dirs = Files.list(http)) {
            return dirs.filter(Files::isDirectory).count();
        }
    }

    @Test
    void rendersAReportFromAPresignedUrlWithoutS3Credentials() throws Exception {
        String url = presign(MAIN, Duration.ofMinutes(1));
        assertThat(url).contains("X-Amz-Signature=").contains("X-Amz-Expires=60");

        MvcResult result = render(url);
        assertThat(text(result)).contains("expression language none: สมชาย ใจดี");
        assertThat(result.getResponse().getContentAsByteArray()).startsWith("%PDF".getBytes(StandardCharsets.US_ASCII));
    }

    @Test
    void aNewSignatureForTheSameObjectIsTheSameBundle() throws Exception {
        String first = presign(MAIN, Duration.ofMinutes(1));
        String second = presign(MAIN, Duration.ofMinutes(5));
        assertThat(second).isNotEqualTo(first);

        MvcResult one = render(first);
        text(one);
        long folders = cachedFolders();
        MvcResult two = render(second);
        text(two);

        assertThat(two.getResponse().getHeader("X-Report-Version")).isEqualTo(one.getResponse().getHeader("X-Report-Version"));
        assertThat(cachedFolders()).as("no new work-dir folder for a new signature").isEqualTo(folders);

        String bundle1 = JsonPath.read(mvc.perform(post("/v1/reports/validate").header("X-API-Key", RenderApiTest.KEY)
                .contentType(MediaType.APPLICATION_JSON).content(body(first))).andReturn().getResponse().getContentAsString(), "$.bundle");
        String bundle2 = JsonPath.read(mvc.perform(post("/v1/reports/validate").header("X-API-Key", RenderApiTest.KEY)
                .contentType(MediaType.APPLICATION_JSON).content(body(second))).andReturn().getResponse().getContentAsString(), "$.bundle");
        assertThat(bundle2).as("bundle id must not depend on the signature").isEqualTo(bundle1);
    }

    @Test
    void anEditedObjectIsPickedUpThroughANewUrl() throws Exception {
        String key = "private/edited.jrxml";
        String original = new String(resource("lang/lang_none.jrxml"), StandardCharsets.UTF_8);
        put(key, original.getBytes(StandardCharsets.UTF_8));
        MvcResult before = render(presign(key, Duration.ofMinutes(1)));
        assertThat(text(before)).contains("expression language none:");

        put(key, original.replace("expression language none: ", "edited in storage: ").getBytes(StandardCharsets.UTF_8));
        MvcResult after = render(presign(key, Duration.ofMinutes(1)));
        assertThat(text(after)).contains("edited in storage:");
        assertThat(after.getResponse().getHeader("X-Report-Version")).isNotEqualTo(before.getResponse().getHeader("X-Report-Version"));
    }

    @Test
    void subReportsCanBePresignedToo() throws Exception {
        MvcResult result = mvc.perform(post("/v1/reports/render").header("X-API-Key", RenderApiTest.KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"mainReport\":{\"url\":\"" + presign("private/main_dir.jrxml", Duration.ofMinutes(1)) + "\"},"
                        + "\"subReports\":[{\"url\":\"" + presign("private/sub_dir.jrxml", Duration.ofMinutes(1)) + "\"}],"
                        + "\"parameters\":[{\"name\":\"hn\",\"value\":\"HN001\"}]}")).andReturn();
        assertThat(text(result)).contains("แบบ SUBREPORT_DIR: สมชาย ใจดี").contains("sub-report (.jasper): visits=2");
    }

    @Test
    void anExpiredUrlIsRefusedByTheStorage() throws Exception {
        String url = presign(MAIN, Duration.ofSeconds(1));
        Thread.sleep(2500);
        mvc.perform(post("/v1/reports/render").header("X-API-Key", RenderApiTest.KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(body(url)))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("STORAGE_ERROR"));
    }

    @Test
    void aUrlThatWorkedButHasExpiredIsNotServedFromTheLastGoodCopy() throws Exception {
        String url = presign(MAIN, Duration.ofSeconds(2));
        text(render(url));
        Thread.sleep(3500);
        MvcResult after = render(url);
        assertThat(after.getResponse().getStatus()).as("expiry must still be enforced after a success").isEqualTo(502);
    }

    @Test
    void aTamperedSignatureIsRefusedAndNeverEchoed() throws Exception {
        String url = presign(MAIN, Duration.ofMinutes(1));
        char last = url.charAt(url.length() - 1);
        String tampered = url.substring(0, url.length() - 1) + (last == '0' ? '1' : '0');

        MvcResult result = render(tampered);
        assertThat(result.getResponse().getStatus()).isEqualTo(502);
        assertThat(result.getResponse().getContentAsString()).doesNotContain("X-Amz").doesNotContain("Signature");
    }

    @Test
    void aMissingObjectIsNotFound() throws Exception {
        mvc.perform(post("/v1/reports/render").header("X-API-Key", RenderApiTest.KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(body(presign("private/nothing.jrxml", Duration.ofMinutes(1)))))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("REPORT_NOT_FOUND"));
    }

    @Test
    void theBucketStaysPrivateWithoutASignature() throws Exception {
        mvc.perform(post("/v1/reports/render").header("X-API-Key", RenderApiTest.KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(body(endpoint() + "/" + BUCKET + "/" + MAIN)))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("STORAGE_ERROR"));
    }
}
