package com.github.somprasongd.jasperreport.api.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

import java.time.ZoneId;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Pins JVM defaults the reports must not depend on the host for.
 * <ul>
 *   <li>time zone = {@code report.timezone}: JDBC and JasperReports format dates in the default zone</li>
 *   <li>locale = {@link Locale#ROOT}: {@link java.util.ResourceBundle} falls back to the <em>JVM default</em> locale
 *       before it uses the base bundle, so with an {@code en_US} host a request for {@code th} would silently
 *       return {@code messages_en.properties} when there is no {@code messages_th.properties}. With the neutral
 *       default the fallback is always the base file {@code messages.properties}. Reports always get an explicit
 *       {@code REPORT_LOCALE}, so nothing else changes.</li>
 * </ul>
 */
@Configuration
public class RuntimeConfig {

    private static final Logger log = LoggerFactory.getLogger(RuntimeConfig.class);

    private final ReportProperties properties;

    public RuntimeConfig(ReportProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void applyTimeZone() {
        ZoneId zone = ZoneId.of(properties.timezone());
        TimeZone.setDefault(TimeZone.getTimeZone(zone));
        Locale.setDefault(Locale.ROOT);
        log.info("Report time zone: {}, JVM default locale: ROOT (reports use REPORT_LOCALE)", zone);
    }
}
