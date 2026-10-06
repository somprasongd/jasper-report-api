package com.github.somprasongd.jasperreport.api;

import com.github.somprasongd.jasperreport.api.config.ReportProperties;
import com.github.somprasongd.jasperreport.api.render.LocaleSelector;
import com.github.somprasongd.jasperreport.api.web.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocaleSelectorTest {

    private static LocaleSelector selector(String configured) {
        Map<String, String> settings = configured == null ? Map.of() : Map.of("report.locale", configured);
        return new LocaleSelector(new Binder(new MapConfigurationPropertySource(settings)).bindOrCreate("report", ReportProperties.class));
    }

    @Test
    void requestBeatsReportBeatsSettingBeatsEnglish() {
        LocaleSelector s = selector("ja");
        assertThat(s.select("en", "th")).isEqualTo(Locale.ENGLISH);
        assertThat(s.select(null, "th")).isEqualTo(Locale.forLanguageTag("th"));
        assertThat(s.select(" ", "th")).isEqualTo(Locale.forLanguageTag("th"));
        assertThat(s.select(null, null)).isEqualTo(Locale.JAPANESE);
        assertThat(selector(null).select(null, null)).isEqualTo(Locale.ENGLISH);
    }

    @Test
    void acceptsUnderscoresAndCountries() {
        assertThat(selector(null).select("en_US", null)).isEqualTo(Locale.US);
        assertThat(selector(null).select("th-TH", null)).isEqualTo(Locale.forLanguageTag("th-TH"));
    }

    @Test
    void invalidValuesFailWithTheRightStatus() {
        assertThatThrownBy(() -> selector(null).select("english please", null)).isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(400));
        assertThatThrownBy(() -> selector(null).select(null, "??")).isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(422));
        assertThatThrownBy(() -> selector("??")).isInstanceOf(IllegalStateException.class);
    }
}
