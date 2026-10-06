package com.github.somprasongd.jasperreport.api.jrxml;

import java.util.Set;

/**
 * {@code net.sf.jasperreports.*} properties that JR 6.21.5 knows and JR 7.0.8 no longer does, found by collecting every such
 * string from the 6.21.5 jar and the 7.0.8 modules and keeping what only the first has. None of them was renamed (no
 * 7.0.8-only property matches a removed one), they went away with their feature: the map component, the interactive
 * header toolbar, the SWF exporter, query executers for languages JR 7 dropped, XML parser settings of the old digester.
 * A property in a JRXML that is not listed here may still be ignored by JR 7: the list is evidence, not a guarantee.
 */
final class RemovedProperties {

    private static final String PREFIX = "net.sf.jasperreports.";

    private static final Set<String> KEYS = Set.of(
            PREFIX + "chrome.executable.path",
            PREFIX + "chrome.page.isolate",
            PREFIX + "chrome.tempdir.path",
            PREFIX + "compiler.xml.parser.cache.schemas",
            PREFIX + "compiler.xml.parser.factory",
            PREFIX + "compiler.xml.validation",
            PREFIX + "components.calendar.date.pattern.bundle",
            PREFIX + "components.calendar.date.pattern.key",
            PREFIX + "components.calendar.date.time.pattern.key",
            PREFIX + "components.date.pattern.bundle",
            PREFIX + "components.date.pattern.key",
            PREFIX + "components.number.pattern.bundle",
            PREFIX + "components.number.pattern.key",
            PREFIX + "components.time.pattern.bundle",
            PREFIX + "components.time.pattern.key",
            PREFIX + "create.sort.fields.for.groups",
            PREFIX + "data.file.service",
            PREFIX + "export.swf.ignore.size",
            PREFIX + "export.xml.parser.factory",
            PREFIX + "export.xml.validation",
            PREFIX + "extension.simple.font.families.default",
            PREFIX + "headertoolbar.column.formatting",
            PREFIX + "query.executer.factory.ejbql",
            PREFIX + "query.executer.factory.hql",
            PREFIX + "query.executer.factory.mdx",
            PREFIX + "query.executer.factory.olap4j",
            PREFIX + "query.executer.factory.plsql",
            PREFIX + "query.executer.factory.xls",
            PREFIX + "query.executer.factory.xlsx",
            PREFIX + "template.xml.parser.factory",
            PREFIX + "viewer.zoom",
            PREFIX + "web.report.interaction.path",
            PREFIX + "web.resource.pattern.default"
    );

    private RemovedProperties() {
    }

    /** Why JR 7 will not act on the property, or null when it is not known to be gone. */
    static String reason(String name) {
        if (name == null || !name.startsWith(PREFIX)) {
            return null;
        }
        if (name.startsWith(PREFIX + "components.map.")) {
            return "the map component was removed in JR 7";
        }
        if (name.startsWith(PREFIX + "components.headertoolbar.") || name.equals(PREFIX + "headertoolbar.column.formatting")) {
            return "the interactive table header toolbar was removed in JR 7";
        }
        if (name.startsWith(PREFIX + "query.executer.factory.")) {
            return "JR 7 has no query executer for that language";
        }
        if (KEYS.contains(name)) {
            return "JR 7.0.8 does not read it any more";
        }
        return null;
    }
}
