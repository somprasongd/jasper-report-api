package com.github.somprasongd.jasperreport.api.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

import java.time.ZoneId;
import java.util.TimeZone;

/**
 * Pins the JVM default time zone to {@code report.timezone}: JDBC and JasperReports format dates in the default
 * zone, so the reports must not depend on the zone of the host or container.
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
        log.info("Report time zone: {}", zone);
    }
}
