package com.github.somprasongd.jasperreport.api.inspect;

import com.github.somprasongd.jasperreport.api.compile.ReportCompiler;
import com.github.somprasongd.jasperreport.api.datasource.DataSourceRegistry;
import com.github.somprasongd.jasperreport.api.params.ParameterBinder;
import com.github.somprasongd.jasperreport.api.render.DatasourceSelector;
import com.github.somprasongd.jasperreport.api.render.RenderRequest;
import com.github.somprasongd.jasperreport.api.source.ResolvedBundle;
import com.github.somprasongd.jasperreport.api.source.SourceResolver;
import net.sf.jasperreports.engine.DefaultJasperReportsContext;
import net.sf.jasperreports.engine.JRParameter;
import net.sf.jasperreports.engine.JasperReport;
import net.sf.jasperreports.engine.fonts.FontUtil;
import org.springframework.stereotype.Component;

import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Backs {@code POST /v1/reports/validate}: compiles a report and tells the author what the API sees in it. */
@Component
public class ReportInspector {

    private static final Pattern FONT_NAME = Pattern.compile("fontName=\"([^\"]+)\"");

    private final DataSourceRegistry datasources;
    private final DatasourceSelector selector;
    private final SourceResolver sources;
    private final ReportCompiler compiler;

    public ReportInspector(DataSourceRegistry datasources, DatasourceSelector selector, SourceResolver sources, ReportCompiler compiler) {
        this.datasources = datasources;
        this.selector = selector;
        this.sources = sources;
        this.compiler = compiler;
    }

    public Map<String, Object> inspect(RenderRequest request, String tenantHeader) {
        String tenant = datasources.resolveTenant(tenantHeader != null && !tenantHeader.isBlank() ? tenantHeader : request.tenant());
        ResolvedBundle bundle = sources.resolve(request.mainReport().url());
        JasperReport report = compiler.main(bundle);
        List<String> warnings = new ArrayList<>();

        String fromFile = report.getProperty(DatasourceSelector.REPORT_PROPERTY);
        String resolved = null;
        try {
            resolved = selector.select(tenant, request.datasource(), fromFile, bundle.mainFile());
        } catch (RuntimeException e) {
            warnings.add("datasource: " + e.getMessage());
        }

        List<Map<String, Object>> parameters = new ArrayList<>();
        for (JRParameter p : report.getParameters()) {
            if (p.isSystemDefined() || ParameterBinder.RESERVED.contains(p.getName())) {
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", p.getName());
            entry.put("class", p.getValueClassName());
            if (p.getNestedTypeName() != null) {
                entry.put("nestedType", p.getNestedTypeName());
            }
            entry.put("hasDefault", p.getDefaultValueExpression() != null);
            parameters.add(entry);
        }

        Set<String> usedFonts = new LinkedHashSet<>();
        scanJrxml(bundle, warnings, usedFonts);
        List<String> missingFonts = usedFonts.stream().filter(f -> !fontAvailable(f)).toList();
        missingFonts.forEach(f -> warnings.add("font '" + f + "' is not available: fill fails unless the font extension provides it"));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("report", bundle.mainFile());
        result.put("bundle", bundle.bundleId());
        result.put("version", bundle.version());
        result.put("tenant", tenant);
        Map<String, Object> ds = new LinkedHashMap<>();
        ds.put("declaredInReport", fromFile);
        ds.put("requested", request.datasource());
        ds.put("resolved", resolved);
        result.put("datasource", ds);
        result.put("parameters", parameters);
        result.put("fonts", Map.of("used", usedFonts, "missing", missingFonts));
        result.put("subreports", jrxmlNames(bundle).stream().filter(n -> !n.equals(bundle.mainFile())).toList());
        result.put("warnings", warnings);
        return result;
    }

    private void scanJrxml(ResolvedBundle bundle, List<String> warnings, Set<String> fonts) {
        for (String name : jrxmlNames(bundle)) {
            try {
                String text = Files.readString(bundle.dir().resolve(name), StandardCharsets.UTF_8);
                Matcher m = FONT_NAME.matcher(text);
                while (m.find()) {
                    fonts.add(m.group(1));
                }
                if (text.contains("$P!{")) {
                    warnings.add(name + ": uses $P!{...} which splices the value into SQL text (SQL injection risk if it comes from a client)");
                }
            } catch (IOException e) {
                warnings.add("cannot read " + name + ": " + e.getMessage());
            }
        }
    }

    private static List<String> jrxmlNames(ResolvedBundle bundle) {
        try (Stream<Path> files = Files.list(bundle.dir())) {
            return files.filter(Files::isRegularFile).map(p -> p.getFileName().toString())
                    .filter(n -> n.toLowerCase(Locale.ROOT).endsWith(".jrxml") && !n.startsWith("."))
                    .sorted().limit(200).toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static boolean fontAvailable(String name) {
        FontUtil fonts = FontUtil.getInstance(DefaultJasperReportsContext.getInstance());
        if (fonts.getFontFamilyNames().contains(name) || fonts.getFontNames().contains(name)) {
            return true;
        }
        return Arrays.asList(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()).contains(name);
    }
}
