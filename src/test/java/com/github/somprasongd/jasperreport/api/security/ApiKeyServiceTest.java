package com.github.somprasongd.jasperreport.api.security;

import com.github.somprasongd.jasperreport.api.config.ReportProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiKeyServiceTest {

    private static final String KEY = "jra_unit_test_key";
    private static final String HASH = ApiKeyService.sha256Hex(KEY);

    private static ApiKeyService service(Map<String, String> settings) {
        Map<String, String> all = new HashMap<>(settings);
        return new ApiKeyService(new Binder(new MapConfigurationPropertySource(all)).bindOrCreate("report", ReportProperties.class));
    }

    @Test
    void authenticatesByHashAndReturnsTheClientId() {
        ApiKeyService s = service(Map.of("report.security.api-keys[0].client-id", "web", "report.security.api-keys[0].sha256", HASH));
        s.validateConfiguration();
        assertThat(s.authenticate(KEY)).contains("web");
        assertThat(s.authenticate("jra_other")).isEmpty();
    }

    @Test
    void disabledAndExpiredKeysAreRejectedAndRotationKeepsBothKeysValid() {
        String next = ApiKeyService.sha256Hex("jra_next");
        ApiKeyService s = service(Map.of(
                "report.security.api-keys[0].client-id", "old", "report.security.api-keys[0].sha256", HASH,
                "report.security.api-keys[0].expires-at", "2000-01-01T00:00:00Z",
                "report.security.api-keys[1].client-id", "off", "report.security.api-keys[1].sha256", ApiKeyService.sha256Hex("jra_off"),
                "report.security.api-keys[1].enabled", "false",
                "report.security.api-keys[2].client-id", "new", "report.security.api-keys[2].sha256", next));
        assertThat(s.authenticate(KEY)).as("expired").isEmpty();
        assertThat(s.authenticate("jra_off")).as("disabled").isEmpty();
        assertThat(s.authenticate("jra_next")).contains("new");
    }

    @Test
    void requiredModeRefusesToStartWithoutAKey() {
        assertThatThrownBy(() -> service(Map.of()).validateConfiguration())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("no enabled key");
        assertThatCode(() -> service(Map.of("report.security.api-key.mode", "optional")).validateConfiguration()).doesNotThrowAnyException();
        assertThatCode(() -> service(Map.of("report.security.api-key.mode", "disabled")).validateConfiguration()).doesNotThrowAnyException();
    }

    @Test
    void malformedHashesAreRejectedAtStartup() {
        assertThatThrownBy(() -> service(Map.of("report.security.api-keys[0].client-id", "web", "report.security.api-keys[0].sha256", "jra_plain_key"))
                .validateConfiguration())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("64-character hex");
    }
}
