package com.github.somprasongd.jasperreport.api;

import com.github.somprasongd.jasperreport.api.params.ParamInput;
import com.github.somprasongd.jasperreport.api.render.OutputFormat;
import com.github.somprasongd.jasperreport.api.render.RenderRequest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The hand-written {@code openapi.yaml} must stay true to the code: routes, request fields, formats and error codes. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OpenApiContractTest {

    private static final Path SPEC_FILE = Path.of("src/main/resources/openapi/openapi.yaml");
    private static final Path MAIN_SOURCES = Path.of("src/main/java");

    private static Map<String, Object> spec;

    @Autowired
    MockMvc mvc;
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    @BeforeAll
    static void load() throws IOException {
        spec = new Yaml().load(Files.readString(SPEC_FILE, StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    private static Map<String, Object> schema(String name) {
        return map(map(map(spec.get("components")).get("schemas")).get(name));
    }

    @Test
    void theApiServesItsOwnContractWithoutAKey() throws Exception {
        mvc.perform(get("/v1/openapi.yaml"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/yaml;charset=UTF-8"))
                .andExpect(content().string(Files.readString(SPEC_FILE, StandardCharsets.UTF_8)));
    }

    @Test
    void everyReferenceResolves() {
        List<String> broken = new ArrayList<>();
        collectBrokenRefs(spec, broken);
        assertThat(broken).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private void collectBrokenRefs(Object node, List<String> broken) {
        if (node instanceof Map<?, ?> m) {
            Object ref = m.get("$ref");
            if (ref instanceof String target) {
                Object resolved = spec;
                for (String part : target.substring(2).split("/")) {
                    resolved = resolved instanceof Map<?, ?> parent ? ((Map<String, Object>) parent).get(part) : null;
                }
                if (resolved == null) {
                    broken.add(target);
                }
            }
            m.values().forEach(v -> collectBrokenRefs(v, broken));
        } else if (node instanceof List<?> list) {
            list.forEach(v -> collectBrokenRefs(v, broken));
        }
    }

    @Test
    void everyDocumentedOperationExistsAndEveryRouteOfTheApiIsDocumented() {
        Set<String> documented = new TreeSet<>();
        map(spec.get("paths")).forEach((path, item) -> {
            if (!path.startsWith("/actuator")) {
                map(item).keySet().stream().filter(k -> Set.of("get", "post", "put", "delete", "patch").contains(k))
                        .forEach(method -> documented.add(method.toUpperCase() + " " + path));
            }
        });

        Set<String> implemented = new TreeSet<>();
        for (Map.Entry<RequestMappingInfo, ?> entry : handlerMapping.getHandlerMethods().entrySet()) {
            Class<?> controller = ((org.springframework.web.method.HandlerMethod) entry.getValue()).getBeanType();
            if (!controller.isAnnotationPresent(RestController.class) || !controller.getPackageName().startsWith("com.github.somprasongd")) {
                continue;
            }
            for (String pattern : entry.getKey().getPathPatternsCondition().getPatternValues()) {
                entry.getKey().getMethodsCondition().getMethods().forEach(m -> implemented.add(m.name() + " " + pattern));
            }
        }
        assertThat(documented).isEqualTo(implemented);
    }

    @Test
    void renderRequestFieldsMatchTheRecord() {
        assertThat(map(schema("RenderRequest").get("properties")).keySet()).isEqualTo(componentNames(RenderRequest.class));
        assertThat(map(schema("ReportRef").get("properties")).keySet()).isEqualTo(componentNames(RenderRequest.ReportRef.class));
        assertThat(map(schema("Parameter").get("properties")).keySet()).isEqualTo(componentNames(ParamInput.class));
        assertThat(schema("RenderRequest").get("required")).isEqualTo(List.of("mainReport"));
        assertThat(schema("ReportRef").get("required")).isEqualTo(List.of("url"));
        assertThat(schema("Parameter").get("required")).isEqualTo(List.of("name"));
    }

    private static Set<String> componentNames(Class<?> record) {
        return Arrays.stream(record.getRecordComponents()).map(RecordComponent::getName).collect(Collectors.toCollection(TreeSet::new));
    }

    @Test
    void formatsMatchTheOutputFormats() {
        Map<String, Object> format = map(map(schema("RenderRequest").get("properties")).get("format"));
        assertThat(format.get("enum")).isEqualTo(Arrays.stream(OutputFormat.values()).map(OutputFormat::extension).toList());
        assertThat(format.get("default")).isEqualTo("pdf");

        Map<String, Object> rendered = map(map(map(spec.get("components")).get("responses")).get("Rendered"));
        Set<String> mediaTypes = map(rendered.get("content")).keySet();
        for (OutputFormat output : OutputFormat.values()) {
            String mediaType = output.contentType().split(";")[0];
            assertThat(mediaTypes).as("response media type of " + output).contains(mediaType);
        }
    }

    @Test
    void errorCodesAreExactlyTheOnesTheCodeCanRaise() throws IOException {
        Set<String> inCode = new TreeSet<>();
        Pattern[] patterns = {
                Pattern.compile("ApiException\\(\\s*[\\w.]+,\\s*\"([A-Z][A-Z_]+)\""),
                Pattern.compile("badRequest\\(\\s*\"([A-Z][A-Z_]+)\""),
                Pattern.compile("reject\\(response,\\s*\"([A-Z][A-Z_]+)\"")};
        try (Stream<Path> files = Files.walk(MAIN_SOURCES)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                for (Pattern pattern : patterns) {
                    Matcher m = pattern.matcher(source);
                    while (m.find()) {
                        inCode.add(m.group(1));
                    }
                }
            }
        }
        @SuppressWarnings("unchecked")
        List<String> documented = (List<String>) schema("ErrorCode").get("enum");
        assertThat(new TreeSet<>(documented)).as("ErrorCode in openapi.yaml vs codes raised in src/main/java").isEqualTo(inCode);
    }
}
