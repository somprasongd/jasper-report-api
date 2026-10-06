package com.github.somprasongd.jasperreport.api.compile;

import com.github.somprasongd.jasperreport.api.source.ResolvedBundle;
import net.sf.jasperreports.engine.JasperReport;

import java.util.AbstractMap;
import java.util.Map;
import java.util.Set;

/**
 * Value of the {@code SUBREPORTS} parameter: {@code $P{SUBREPORTS}.get("sub_x")} compiles {@code sub_x.jrxml} of
 * the same bundle on first use and returns the cached {@link JasperReport}.
 */
public class LazySubreports extends AbstractMap<String, JasperReport> {

    private final ReportCompiler compiler;
    private final ResolvedBundle bundle;

    public LazySubreports(ReportCompiler compiler, ResolvedBundle bundle) {
        this.compiler = compiler;
        this.bundle = bundle;
    }

    @Override
    public JasperReport get(Object key) {
        return key == null ? null : compiler.named(bundle, key.toString());
    }

    @Override
    public boolean containsKey(Object key) {
        return key != null;
    }

    @Override
    public Set<Entry<String, JasperReport>> entrySet() {
        return Map.<String, JasperReport>of().entrySet();
    }
}
