package com.github.somprasongd.jasperreport.api;

import com.github.somprasongd.jasperreport.api.config.ReportProperties;
import com.github.somprasongd.jasperreport.api.params.ParamInput;
import com.github.somprasongd.jasperreport.api.params.ParameterBinder;
import com.github.somprasongd.jasperreport.api.web.ApiException;
import net.sf.jasperreports.engine.DefaultJasperReportsContext;
import net.sf.jasperreports.engine.JasperCompileManager;
import net.sf.jasperreports.engine.JasperReport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.io.FileInputStream;
import java.math.BigDecimal;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ParameterBinderTest {

    static JasperReport report;
    static ParameterBinder binder;
    static ParameterBinder strictBinder;

    @BeforeAll
    static void setUp() throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Bangkok"));
        try (var in = new FileInputStream("src/test/resources/reports/params/params_demo.jrxml")) {
            report = JasperCompileManager.getInstance(DefaultJasperReportsContext.getInstance()).compile(in);
        }
        binder = new ParameterBinder(props("false"));
        strictBinder = new ParameterBinder(props("true"));
    }

    private static ReportProperties props(String strict) {
        return new Binder(new MapConfigurationPropertySource(Map.of("report.parameters.strict", strict)))
                .bindOrCreate("report", ReportProperties.class);
    }

    private static Map<String, Object> bind(ParamInput... inputs) {
        return binder.bind(report, List.of(inputs));
    }

    @Test
    void convertsToTheDeclaredClassWhateverTheJsonType() {
        Map<String, Object> p = bind(
                new ParamInput("s", null, 42), new ParamInput("i", null, "7"), new ParamInput("l", "integer", 9007199254740993L),
                new ParamInput("d", null, 3), new ParamInput("bd", null, "12.50"), new ParamInput("b", "bool", "true"));
        assertThat(p.get("s")).isEqualTo("42");
        assertThat(p.get("i")).isEqualTo(7);
        assertThat(p.get("l")).isEqualTo(9007199254740993L);
        assertThat(p.get("d")).isEqualTo(3.0d);
        assertThat(p.get("bd")).isEqualTo(new BigDecimal("12.50"));
        assertThat(p.get("b")).isEqualTo(true);
    }

    @Test
    void parsesDatesTimesAndTimestampsInReportTimeZone() {
        Map<String, Object> p = bind(
                new ParamInput("date", null, "2026-10-06"),
                new ParamInput("utilDate", null, "2026-10-06"),
                new ParamInput("time", null, "10:30:15"),
                new ParamInput("ts", null, "2026-10-06T10:30:00+07:00"));
        assertThat(p.get("date")).isEqualTo(java.sql.Date.valueOf("2026-10-06"));
        assertThat(p.get("utilDate")).isInstanceOf(java.sql.Date.class);
        assertThat(p.get("time")).isEqualTo(Time.valueOf("10:30:15"));
        assertThat(((Timestamp) p.get("ts")).toLocalDateTime()).isEqualTo(LocalDateTime.of(2026, 10, 6, 10, 30));

        // same instant written in UTC, with a space instead of T, without offset, and as the old pdf style "Z"
        assertThat(((Timestamp) bind(new ParamInput("ts", null, "2026-10-06T03:30:00Z")).get("ts")).toLocalDateTime())
                .isEqualTo(LocalDateTime.of(2026, 10, 6, 10, 30));
        assertThat(((Timestamp) bind(new ParamInput("ts", null, "2026-10-06 10:30:00")).get("ts")).toLocalDateTime())
                .isEqualTo(LocalDateTime.of(2026, 10, 6, 10, 30));
        assertThat(bind(new ParamInput("time", null, "03:30:00Z")).get("time")).isEqualTo(Time.valueOf("10:30:00"));
        assertThat(bind(new ParamInput("time", null, "10:30:00+07:00")).get("time")).isEqualTo(Time.valueOf("10:30:00"));
    }

    @Test
    void collectionsAcceptJsonArraysAndCommaSeparatedStringsWithHints() {
        Map<String, Object> p = bind(
                new ParamInput("ids", "array_int", "1,2, 3"),
                new ParamInput("names", "array_str", List.of("a", "b")),
                new ParamInput("anything", "array_int", List.of(5, "6")));
        assertThat(p.get("ids")).isEqualTo(List.of(1, 2, 3));
        assertThat(p.get("names")).isEqualTo(List.of("a", "b"));
        assertThat(p.get("anything")).isEqualTo(List.of(5, 6));
    }

    @Test
    void looselyTypedParametersUseTheHintOrKeepTheRawValue() {
        assertThat(bind(new ParamInput("anything", "integer", "12")).get("anything")).isEqualTo(12);
        assertThat(bind(new ParamInput("anything", null, "12")).get("anything")).isEqualTo("12");
        assertThat(bind(new ParamInput("anything", "date", "2026-10-06")).get("anything")).isEqualTo(java.sql.Date.valueOf("2026-10-06"));
    }

    @Test
    void nullStaysNullAndReservedOrUndeclaredNamesAreDropped() {
        Map<String, Object> p = bind(
                new ParamInput("s", null, null), new ParamInput("SUBREPORT_DIR", null, "/etc/"),
                new ParamInput("IMAGE_DIR", null, "/etc/"), new ParamInput("REPORT_CONNECTION", null, "x"),
                new ParamInput("nope", null, "x"));
        assertThat(p).containsOnlyKeys("s");
        assertThat(p.get("s")).isNull();
    }

    @Test
    void strictModeRejectsUndeclaredParameters() {
        assertThatThrownBy(() -> strictBinder.bind(report, List.of(new ParamInput("nope", null, "x"))))
                .isInstanceOf(ApiException.class).hasMessageContaining("nope");
    }

    @Test
    void invalidValuesNameTheParameter() {
        for (ParamInput bad : List.of(new ParamInput("i", null, "abc"), new ParamInput("b", null, "maybe"),
                new ParamInput("date", null, "06/10/2026"), new ParamInput("time", null, "25:99"),
                new ParamInput("ts", null, "yesterday"), new ParamInput("anything", "wat", "x"))) {
            assertThatThrownBy(() -> bind(bad)).isInstanceOf(ApiException.class)
                    .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("PARAMETER_INVALID"))
                    .hasMessageContaining("'" + bad.name() + "'");
        }
    }
}
