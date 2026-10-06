package com.github.somprasongd.jasperreport.api.config;

import com.github.somprasongd.jasperreport.api.datasource.DataSourceRegistry;
import com.github.somprasongd.jasperreport.api.source.SourceResolver;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;

/** Readiness: every configured datasource and the S3 store (when configured) must be reachable. */
@Configuration
public class ReadinessIndicators {

    @Bean
    HealthIndicator reportDatasources(DataSourceRegistry registry) {
        return () -> {
            Map<String, Object> details = new LinkedHashMap<>();
            boolean up = true;
            for (String key : registry.allKeys()) {
                try (Connection c = registry.getByKey(key).getConnection()) {
                    boolean valid = c.isValid(2);
                    details.put(key, valid ? "UP" : "DOWN");
                    up &= valid;
                } catch (Exception e) {
                    details.put(key, "DOWN: " + e.getClass().getSimpleName());
                    up = false;
                }
            }
            return (up ? Health.up() : Health.down()).withDetails(details).build();
        };
    }

    @Bean
    HealthIndicator reportStorage(SourceResolver sources, ReportProperties properties) {
        return () -> {
            if (!properties.sources().s3().configured()) {
                return Health.up().withDetail("s3", "not configured").build();
            }
            try {
                sources.probeStorage(properties.sources().s3());
                return Health.up().withDetail("s3", "UP").build();
            } catch (Exception e) {
                return Health.down().withDetail("s3", "DOWN: " + e.getClass().getSimpleName()).build();
            }
        };
    }
}
