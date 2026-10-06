package com.github.somprasongd.jasperreport.api.datasource;

import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.Map;

/** One entry of the top-level {@code tenants.*} configuration. */
public record TenantProperties(
        String defaultDatasource,
        @DefaultValue Map<String, DatasourceProperties> datasources) {

    public record DatasourceProperties(
            String url,
            String username,
            String password,
            @DefaultValue("5") int poolSize,
            @DefaultValue("true") boolean readOnly) {
    }
}
