package com.github.somprasongd.jasperreport.api.datasource;

import com.github.somprasongd.jasperreport.api.config.ReportProperties;
import com.github.somprasongd.jasperreport.api.render.DataPlan;
import com.github.somprasongd.jasperreport.api.web.ApiException;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Named JDBC datasources per tenant, created lazily from {@code tenants.*} configuration. Clients only ever
 * pass logical names; credentials never leave the server.
 */
@Component
public class DataSourceRegistry {

    public static final String DEFAULT_TENANT = "default";
    private static final Logger log = LoggerFactory.getLogger(DataSourceRegistry.class);

    private final Map<String, TenantProperties> tenants;
    private final ReportProperties.Limits limits;
    private final Map<String, HikariDataSource> pools = new ConcurrentHashMap<>();

    public DataSourceRegistry(Environment environment, ReportProperties properties) {
        Map<String, TenantProperties> bound = Binder.get(environment)
                .bind("tenants", Bindable.mapOf(String.class, TenantProperties.class))
                .orElseGet(Map::of);
        this.tenants = new LinkedHashMap<>();
        this.limits = properties.limits();
        bound.forEach((name, tenant) -> {
            // a datasource whose url is empty (e.g. an unset environment variable) is treated as not configured
            Map<String, TenantProperties.DatasourceProperties> usable = new LinkedHashMap<>();
            tenant.datasources().forEach((dsName, ds) -> {
                if (DataPlan.isNone(dsName)) {
                    log.warn("tenants.{}.datasources.{} ignored: '{}' is reserved for \"no database\"", name, dsName, DataPlan.NONE_NAME);
                } else if (ds.url() != null && !ds.url().isBlank()) {
                    usable.put(dsName, ds);
                }
            });
            String defaultDs = tenant.defaultDatasource();
            if (defaultDs != null && !usable.containsKey(defaultDs)) {
                log.warn("tenants.{}.default-datasource '{}' has no url; requests must name a datasource", name, defaultDs);
                defaultDs = null;
            }
            if (!usable.isEmpty()) {
                this.tenants.put(name, new TenantProperties(defaultDs, usable));
            }
        });
        log.info("Configured tenants: {}", tenants.keySet());
    }

    /** Resolves the tenant name from request input; falls back to {@code default}. */
    public String resolveTenant(String requested) {
        String tenant = requested == null || requested.isBlank() ? DEFAULT_TENANT : requested.trim();
        if (!tenants.containsKey(tenant)) {
            throw ApiException.badRequest("TENANT_UNKNOWN", "tenant '" + tenant + "' is not configured");
        }
        return tenant;
    }

    /**
     * The tenant a request names; a request that names none gets {@code default} without checking it exists, so a
     * render that needs no database works on a server with no tenants at all.
     */
    public String requestedTenant(String requested) {
        return requested == null || requested.isBlank() ? DEFAULT_TENANT : resolveTenant(requested);
    }

    /** @return the tenant's default datasource name, or null when none is configured. */
    public String defaultDatasource(String tenant) {
        TenantProperties props = tenants.get(tenant);
        return props == null ? null : props.defaultDatasource();
    }

    public boolean exists(String tenant, String name) {
        TenantProperties props = tenants.get(tenant);
        return props != null && props.datasources().containsKey(name);
    }

    public DataSource get(String tenant, String name) {
        TenantProperties props = tenants.get(tenant);
        if (props == null || !props.datasources().containsKey(name)) {
            throw ApiException.badRequest("DATASOURCE_UNKNOWN",
                    "datasource '" + name + "' is not configured for tenant '" + tenant + "'");
        }
        return pools.computeIfAbsent(tenant + "/" + name, key -> create(key, props.datasources().get(name)));
    }

    /** "tenant/name" of every configured datasource, for health checks. */
    public List<String> allKeys() {
        List<String> keys = new java.util.ArrayList<>();
        tenants.forEach((tenant, props) -> props.datasources().keySet().forEach(name -> keys.add(tenant + "/" + name)));
        return keys;
    }

    public DataSource getByKey(String key) {
        int slash = key.indexOf('/');
        return get(key.substring(0, slash), key.substring(slash + 1));
    }

    private HikariDataSource create(String key, TenantProperties.DatasourceProperties props) {
        if (props.url() == null || props.url().isBlank()) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "DATASOURCE_MISCONFIGURED",
                    "datasource '" + key + "' has no url");
        }
        HikariConfig config = new HikariConfig();
        config.setPoolName("report-" + key.replace('/', '-'));
        config.setJdbcUrl(props.url());
        config.setUsername(props.username());
        config.setPassword(props.password());
        config.setMaximumPoolSize(props.poolSize());
        config.setMinimumIdle(0);
        config.setReadOnly(props.readOnly());
        config.setInitializationFailTimeout(-1); // do not fail the whole API when one database is down at startup
        long queryTimeoutMs = limits.queryTimeout().toMillis();
        if (props.url().startsWith("jdbc:postgresql:") && queryTimeoutMs > 0) {
            config.setConnectionInitSql("SET statement_timeout = " + queryTimeoutMs);
        }
        return new HikariDataSource(config);
    }

    @PreDestroy
    void close() {
        pools.values().forEach(HikariDataSource::close);
    }
}
