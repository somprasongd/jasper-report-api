package com.github.somprasongd.jasperreport.api.jrxml;

import net.sf.jasperreports.engine.JRDataSource;
import net.sf.jasperreports.engine.JREmptyDataSource;
import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JasperCompileManager;
import net.sf.jasperreports.engine.JasperFillManager;
import net.sf.jasperreports.engine.JasperPrint;
import net.sf.jasperreports.engine.JasperReport;
import net.sf.jasperreports.engine.data.JRMapCollectionDataSource;
import net.sf.jasperreports.engine.design.JasperDesign;
import net.sf.jasperreports.engine.xml.JRXmlLoader;
import net.sf.jasperreports.engine.xml.JRXmlWriter;
import net.sf.jasperreports.export.SimpleExporterInput;
import net.sf.jasperreports.export.SimpleOutputStreamExporterOutput;
import net.sf.jasperreports.pdf.JRPdfExporter;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.w3c.dom.Document;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What JasperReports 7.0.8 makes of the converted reports: they load, compile, survive a round trip through
 * {@code JRXmlWriter} (JR 7's loader is strict about unknown names but silently drops some misplaced content, so
 * "it loads" alone proves little) and render the text they should.
 */
class LegacyJrxmlJr7Test {

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    // ---------------------------------------------------------------- load and compile

    @ParameterizedTest
    @ValueSource(strings = {"basic_legacy.jrxml", "dtd_legacy.jrxml", "subreports_main.jrxml", "subreports_sub.jrxml", "components.jrxml"})
    void convertedReportsLoadAndCompileOnJr7(String fixture) throws Exception {
        JasperDesign design = load(Jrxml6Fixtures.convert(fixture).jrxml());
        JasperReport report = JasperCompileManager.compileReport(design);
        assertThat(report.getName()).isEqualTo(fixture.replace(".jrxml", ""));
    }

    @Test
    void theReportWithUnsupportedConstructsStillLoadsWithTheRestIntact() throws Exception {
        String converted = Jrxml6Fixtures.convert("unsupported.jrxml").jrxml();
        // JR 7 does not even load a query in a language without an executer, nor a barbecue component without its module
        // (this API ships neither): the converter's warnings are the early notice of both
        assertThatThrownBy(() -> load(converted)).hasStackTraceContaining("No query executer factory registered for the \"plsql\" language");
        String withoutQuery = converted.replace("language=\"plsql\"", "language=\"sql\"");
        assertThatThrownBy(() -> load(withoutQuery)).hasStackTraceContaining("Could not resolve type id 'barbecue'");
        String withoutBarbecue = withoutQuery.replaceAll("(?s)<element kind=\"component\"[^>]*>\\s*<component kind=\"barbecue\".*?</element>", "");
        JasperDesign design = load(withoutBarbecue);
        // the chart and the map are gone; the two static texts and the textField remain
        assertThat(design.getTitle().getElements()).hasSize(3);
        assertThat(design.getImports()).containsExactly("net.sf.jasperreports.engine.data.JsonDataSource");
    }

    @Test
    void theLoaderRejectsUnknownNamesButSilentlyDropsSomeMisplacedContent() throws Exception {
        String report = "<jasperReport name=\"t\" pageWidth=\"595\" pageHeight=\"842\" columnWidth=\"555\" leftMargin=\"20\" rightMargin=\"20\" topMargin=\"20\" bottomMargin=\"20\">%s"
                + "<title height=\"50\">%s</title></jasperReport>";
        String textField = "<element kind=\"textField\" x=\"0\" y=\"0\" width=\"100\" height=\"20\"%s><expression><![CDATA[\"x\"]]></expression>%s</element>";
        // an unknown attribute or element is an error: nothing that was not understood is carried along unnoticed
        assertThatThrownBy(() -> load(report.formatted("", textField.formatted(" isBold=\"true\"", "")))).hasStackTraceContaining("isBold");
        assertThatThrownBy(() -> load(report.formatted("", textField.formatted("", "<bogus/>")))).hasStackTraceContaining("bogus");
        // ... but a JR 6 style <import value=".."/> is read as an empty import without a word, which is why the round trip below matters
        assertThat(load(report.formatted("<import value=\"a.B\"/>", "")).getImports()).containsExactly("");
        assertThat(load(report.formatted("<import>a.B</import>", "")).getImports()).containsExactly("a.B");
    }

    // ---------------------------------------------------------------- round trip

    @Test
    void theJr7WriterGivesBackWhatWasConverted() throws Exception {
        String converted = Jrxml6Fixtures.convert("basic_legacy.jrxml").jrxml();
        String written = JRXmlWriter.writeReport(load(converted), "UTF-8");
        Document doc = Jrxml6Fixtures.dom(written);
        List<String[]> expectations = List.of(
                // report
                new String[]{"/jasperReport/@summaryWithPageHeaderAndFooter", "true"},
                new String[]{"/jasperReport/@floatColumnFooter", "true"},
                new String[]{"/jasperReport/@whenNoDataType", "NoDataSection"},
                new String[]{"/jasperReport/@uuid", "11111111-1111-4111-8111-000000000001"},
                new String[]{"/jasperReport/property[@name='ireport.zoom']/@value", "1.5"},
                new String[]{"/jasperReport/propertyExpression[@name='net.sf.jasperreports.export.pdf.title']", "\"Basic \" + $P{who}"},
                new String[]{"count(/jasperReport/import[.='java.text.*'])", "1"},
                new String[]{"count(/jasperReport/import[.='java.util.*'])", "1"},
                new String[]{"/jasperReport/query/@language", "sql"},
                new String[]{"/jasperReport/query", "select grp, item, qty from items order by grp, item"},
                // styles
                new String[]{"/jasperReport/style[@name='Base']/@default", "true"},
                new String[]{"/jasperReport/style[@name='Header']/@style", "Base"},
                new String[]{"/jasperReport/style[@name='Header']/@hTextAlign", "Center"},
                new String[]{"/jasperReport/style[@name='Header']/@vTextAlign", "Middle"},
                new String[]{"/jasperReport/style[@name='Header']/@bold", "true"},
                new String[]{"/jasperReport/style[@name='Header']/@italic", "true"},
                new String[]{"/jasperReport/style[@name='Header']/@pdfFontName", "Helvetica-Bold"},
                new String[]{"/jasperReport/style[@name='Header']/@radius", "2"},
                new String[]{"/jasperReport/style[@name='Header']/pen/@lineStyle", "Dashed"},
                new String[]{"/jasperReport/style[@name='Header']/box/@topPadding", "3"},
                new String[]{"/jasperReport/style[@name='Header']/box/topPen/@lineStyle", "Dotted"},
                new String[]{"/jasperReport/style[@name='Header']/box/bottomPen/@lineWidth", "0.25"},
                new String[]{"/jasperReport/style[@name='Header']/paragraph/@lineSpacing", "1_1_2"},
                new String[]{"/jasperReport/style[@name='Header']/paragraph/@spacingAfter", "5"},
                new String[]{"/jasperReport/style[@name='Header']/paragraph/tabStop/@position", "60"},
                new String[]{"/jasperReport/style[@name='Zebra']/conditionalStyle/@backcolor", "#EEEEEE"},
                new String[]{"/jasperReport/style[@name='Zebra']/conditionalStyle/@bold", "true"},
                new String[]{"/jasperReport/style[@name='Zebra']/conditionalStyle/conditionExpression", "$V{REPORT_COUNT} % 2 == 0"},
                new String[]{"/jasperReport/style[@name='Zebra']/conditionalStyle/box/@padding", "1"},
                // parameters, fields, variables, group
                new String[]{"/jasperReport/parameter[@name='who']/description", "Who to greet"},
                new String[]{"/jasperReport/parameter[@name='who']/defaultValueExpression", "\"World\""},
                new String[]{"/jasperReport/parameter[@name='threshold']/@forPrompting", "false"},
                new String[]{"/jasperReport/field[@name='grp']/description", "group name"},
                new String[]{"/jasperReport/field[@name='qty']/property[@name='field.note']/@value", "n"},
                new String[]{"/jasperReport/sortField/@name", "grp"},
                new String[]{"/jasperReport/variable[@name='total_qty']/expression", "$F{qty}"},
                new String[]{"/jasperReport/variable[@name='total_qty']/initialValueExpression", "Integer.valueOf(0)"},
                new String[]{"/jasperReport/variable[@name='grp_qty']/@resetGroup", "by_grp"},
                new String[]{"/jasperReport/variable[@name='grp_qty']/@resetType", "Group"},
                new String[]{"/jasperReport/filterExpression", "$F{qty} != null"},
                new String[]{"/jasperReport/group/@minHeightToStartNewPage", "30"},
                new String[]{"/jasperReport/group/@keepTogether", "true"},
                new String[]{"/jasperReport/group/@reprintHeaderOnEachPage", "true"},
                new String[]{"/jasperReport/group/expression", "$F{grp}"},
                new String[]{"/jasperReport/group/groupHeader/band/@height", "22"},
                new String[]{"/jasperReport/group/groupHeader/band/element/@style", "Header"},
                // bands
                new String[]{"/jasperReport/title/@height", "110"},
                new String[]{"/jasperReport/title/printWhenExpression", "Boolean.TRUE"},
                new String[]{"/jasperReport/pageHeader/@height", "24"},
                new String[]{"/jasperReport/detail/band[1]/@splitType", "Prevent"},
                new String[]{"count(/jasperReport/detail/band)", "2"},
                new String[]{"/jasperReport/summary/@splitType", "Prevent"},
                new String[]{"/jasperReport/lastPageFooter/@height", "20"},
                new String[]{"/jasperReport/noData/@height", "20"},
                // static text
                new String[]{"//element[@key='greeting-label']/@uuid", "11111111-1111-4111-8111-000000000011"},
                new String[]{"//element[@key='greeting-label']/@fontSize", "12.0"},
                new String[]{"//element[@key='greeting-label']/@bold", "true"},
                new String[]{"//element[@key='greeting-label']/@italic", "true"},
                new String[]{"//element[@key='greeting-label']/@underline", "true"},
                new String[]{"//element[@key='greeting-label']/@hTextAlign", "Center"},
                new String[]{"//element[@key='greeting-label']/@vTextAlign", "Bottom"},
                new String[]{"//element[@key='greeting-label']/@forecolor", "#990000"},
                new String[]{"//element[@key='greeting-label']/@backcolor", "#FFFFCC"},
                new String[]{"//element[@key='greeting-label']/paragraph/@lineSpacing", "Double"},
                new String[]{"//element[@key='greeting-label']/box/@padding", "2"},
                new String[]{"//element[@key='greeting-label']/property[@name='element.note']/@value", "static"},
                new String[]{"//element[@key='greeting-label']/propertyExpression[@name='element.expr']", "\"e\""},
                new String[]{"//element[@key='greeting-label']/text", "Greeting <&> text"},
                // text field with hyperlinks
                new String[]{"//element[@textAdjust='StretchHeight']/@linkType", "Reference"},
                new String[]{"//element[@textAdjust='StretchHeight']/@linkTarget", "Blank"},
                new String[]{"//element[@textAdjust='StretchHeight']/@bookmarkLevel", "1"},
                new String[]{"//element[@textAdjust='StretchHeight']/@positionType", "Float"},
                new String[]{"//element[@textAdjust='StretchHeight']/@stretchType", "ElementGroupHeight"},
                new String[]{"//element[@textAdjust='StretchHeight']/@printRepeatedValues", "false"},
                new String[]{"//element[@textAdjust='StretchHeight']/@removeLineWhenBlank", "true"},
                new String[]{"//element[@textAdjust='StretchHeight']/@printInFirstWholeBand", "true"},
                new String[]{"//element[@textAdjust='StretchHeight']/@printWhenDetailOverflows", "true"},
                new String[]{"//element[@textAdjust='StretchHeight']/@printWhenGroupChanges", "by_grp"},
                new String[]{"//element[@textAdjust='StretchHeight']/@blankWhenNull", "true"},
                new String[]{"//element[@textAdjust='StretchHeight']/@hTextAlign", "Justified"},
                new String[]{"//element[@textAdjust='StretchHeight']/anchorNameExpression", "\"greet\""},
                new String[]{"//element[@textAdjust='StretchHeight']/bookmarkLevelExpression", "Integer.valueOf(1)"},
                new String[]{"//element[@textAdjust='StretchHeight']/hyperlinkReferenceExpression", "\"http://example.org/\" + $P{who}"},
                new String[]{"//element[@textAdjust='StretchHeight']/hyperlinkWhenExpression", "Boolean.TRUE"},
                new String[]{"//element[@textAdjust='StretchHeight']/hyperlinkTooltipExpression", "\"tip\""},
                new String[]{"//element[@textAdjust='StretchHeight']/hyperlinkParameter[@name='p1']/expression", "\"v1\""},
                new String[]{"//element[@evaluationTime='Group']/@evaluationGroup", "by_grp"},
                new String[]{"//element[@pattern='#,##0.00']/@evaluationTime", "Report"},
                // image, line, rectangle, ellipse, break, frame
                new String[]{"//element[@kind='image']/@scaleImage", "RetainShape"},
                new String[]{"//element[@kind='image']/@hImageAlign", "Center"},
                new String[]{"//element[@kind='image']/@vImageAlign", "Middle"},
                new String[]{"//element[@kind='image']/@usingCache", "true"},
                new String[]{"//element[@kind='image']/@onErrorType", "Blank"},
                new String[]{"//element[@kind='image']/box/pen/@lineWidth", "0.5"},
                new String[]{"//element[@kind='image']/pen/@lineStyle", "Dotted"},
                new String[]{"//element[@kind='line']/@direction", "BottomUp"},
                new String[]{"//element[@kind='line']/@forecolor", "#0000FF"},
                new String[]{"//element[@kind='line']/pen/@lineWidth", "2.0"},
                new String[]{"//element[@kind='line']/pen/@lineColor", "#FF0000"},
                new String[]{"//element[@kind='rectangle']/@radius", "5"},
                new String[]{"//element[@kind='ellipse']/pen/@lineWidth", "1.5"},
                new String[]{"//element[@kind='break']/@type", "Column"},
                new String[]{"//element[@kind='break']/printWhenExpression", "Boolean.FALSE"},
                new String[]{"//element[@kind='frame']/box/leftPen/@lineWidth", "3.0"},
                new String[]{"//element[@kind='frame']/box/pen/@lineColor", "#999999"},
                new String[]{"//element[@kind='frame']/element[@kind='staticText']/text", "Inside frame"},
                new String[]{"//element[@kind='frame']/element[@kind='elementGroup']/element/expression", "\"Grouped \" + $P{threshold}"});
        for (String[] e : expectations) {
            assertThat(Jrxml6Fixtures.xpath(doc, e[0])).as(e[0]).isEqualTo(e[1]);
        }
    }

    @Test
    void theWriterAlsoGivesBackTheDtdEraReportAndTheSubreports() throws Exception {
        Document dtd = Jrxml6Fixtures.dom(JRXmlWriter.writeReport(load(Jrxml6Fixtures.convert("dtd_legacy.jrxml").jrxml()), "UTF-8"));
        assertThat(Jrxml6Fixtures.xpath(dtd, "//element[@key='staticText-1']/@style")).isEqualTo("Bold");
        assertThat(Jrxml6Fixtures.xpath(dtd, "//element[@key='staticText-1']/@markup")).isEqualTo("styled");
        assertThat(Jrxml6Fixtures.xpath(dtd, "//element[@key='staticText-1']/@stretchType")).isEqualTo("ContainerHeight");
        assertThat(Jrxml6Fixtures.xpath(dtd, "//element[@key='staticText-1']/box/leftPen/@lineWidth")).isEqualTo("0.5");
        assertThat(Jrxml6Fixtures.xpath(dtd, "//element[@key='staticText-1']/box/topPen/@lineStyle")).isEqualTo("Dashed");
        assertThat(Jrxml6Fixtures.xpath(dtd, "//element[@key='staticText-1']/paragraph/@lineSpacing")).isEqualTo("1_1_2");
        assertThat(Jrxml6Fixtures.xpath(dtd, "//element[@key='textField-1']/@fontSize")).isEqualTo("11.0");
        assertThat(Jrxml6Fixtures.xpath(dtd, "//element[@kind='rectangle']/pen/@lineWidth")).isEqualTo("0.5");
        assertThat(Jrxml6Fixtures.xpath(dtd, "//element[@kind='line']/pen/@lineWidth")).isEqualTo("4.0");
        assertThat(Jrxml6Fixtures.xpath(dtd, "/jasperReport/style[@name='Bold']/@bold")).isEqualTo("true");

        Document main = Jrxml6Fixtures.dom(JRXmlWriter.writeReport(load(Jrxml6Fixtures.convert("subreports_main.jrxml").jrxml()), "UTF-8"));
        String conn = "//element[@key='sub_conn']";
        assertThat(Jrxml6Fixtures.xpath(main, conn + "/@usingCache")).isEqualTo("true");
        assertThat(Jrxml6Fixtures.xpath(main, conn + "/parameter[@name='limit']/expression")).isEqualTo("Integer.valueOf(2)");
        assertThat(Jrxml6Fixtures.xpath(main, conn + "/connectionExpression")).isEqualTo("$P{REPORT_CONNECTION}");
        assertThat(Jrxml6Fixtures.xpath(main, conn + "/returnValue/@toVariable")).isEqualTo("from_conn");
        assertThat(Jrxml6Fixtures.xpath(main, conn + "/returnValue/@calculation")).isEqualTo("Sum");
        assertThat(Jrxml6Fixtures.xpath(main, "//element[@key='sub_ds']/dataSourceExpression")).isEqualTo("$P{ROWS}");
        assertThat(Jrxml6Fixtures.xpath(main, "//element[@key='sub_ds']/parametersMapExpression")).isEqualTo("$P{SUB_PARAMS}");

        Document components = Jrxml6Fixtures.dom(JRXmlWriter.writeReport(load(Jrxml6Fixtures.convert("components.jrxml").jrxml()), "UTF-8"));
        assertThat(Jrxml6Fixtures.xpath(components, "//component[@kind='table']/@whenNoDataType")).isEqualTo("NoDataCell");
        assertThat(Jrxml6Fixtures.xpath(components, "//component[@kind='table']/datasetRun/parameter[@name='minQty']/expression")).isEqualTo("Integer.valueOf(4)");
        assertThat(Jrxml6Fixtures.xpath(components, "//component[@kind='table']/column[1]/groupHeader/@groupName")).isEqualTo("by_grp");
        assertThat(Jrxml6Fixtures.xpath(components, "//component[@kind='table']/column[2]/column[2]/columnFooter/@height")).isEqualTo("18");
        assertThat(Jrxml6Fixtures.xpath(components, "//component[@kind='list']/@printOrder")).isEqualTo("Horizontal");
        assertThat(Jrxml6Fixtures.xpath(components, "//component[@kind='barcode4j:Code128']/@textPosition")).isEqualTo("bottom");
        assertThat(Jrxml6Fixtures.xpath(components, "//component[@kind='barcode4j:Code39']/@orientation")).isEqualTo("left");
        assertThat(Jrxml6Fixtures.xpath(components, "//component[@kind='barcode4j:Code39']/@wideFactor")).isEqualTo("2.5");
        assertThat(Jrxml6Fixtures.xpath(components, "//component[@kind='barcode4j:QRCode']/@margin")).isEqualTo("2");
        assertThat(Jrxml6Fixtures.xpath(components, "//element[@kind='crosstab']/rowGroup/@totalPosition")).isEqualTo("End");
        assertThat(Jrxml6Fixtures.xpath(components, "//element[@kind='crosstab']/measure/@calculation")).isEqualTo("Sum");
        assertThat(Jrxml6Fixtures.xpath(components, "//element[@kind='crosstab']/headerCell/@backcolor")).isEqualTo("#F0F0F0");
    }

    // ---------------------------------------------------------------- rendering

    @Test
    void theBasicReportRendersWithGroupsVariablesAndAllTheElements() throws Exception {
        try (Connection c = h2()) {
            JasperPrint print = fill("basic_legacy.jrxml", Map.of(), c, null);
            assertThat(print.getPages()).hasSize(1);
            String text = text(print);
            // (the first static text wraps inside its box; a one-page report ends with the last page footer, not the page footer)
            assertThat(text).contains("Greeting <&>", "Hello World", "Item", "Quantity", "Group A", "Group B", "apple", "pear", "fig")
                    .contains("Subtotal A: 7", "Subtotal B: 5", "Total 12", "Inside frame", "Grouped 4", "Last page footer", "12.00", "003", "005");
            assertThat(text).doesNotContain("No data");
        }
    }

    @Test
    void theBasicReportRendersItsNoDataBandWhenTheQueryFindsNothing() throws Exception {
        try (Connection c = h2()) {
            c.createStatement().execute("delete from items");
            JasperPrint print = fill("basic_legacy.jrxml", Map.of(), c, null);
            assertThat(text(print)).contains("No data").doesNotContain("Subtotal");
        }
    }

    @Test
    void theDtdEraReportRenders() throws Exception {
        JasperPrint print = fill("dtd_legacy.jrxml", Map.of("who", "DTD"), null, new JREmptyDataSource(1));
        assertThat(text(print)).contains("Hello DTD", "era", "Pages: 1");
    }

    @Test
    void subreportsRunOnTheParentsConnectionAndOnADataSourceAndReturnTheirValues() throws Exception {
        JasperReport sub = JasperCompileManager.compileReport(load(Jrxml6Fixtures.convert("subreports_sub.jrxml").jrxml()));
        try (Connection c = h2()) {
            Map<String, Object> params = new HashMap<>();
            params.put("SUB", sub);
            params.put("ROWS", new JRMapCollectionDataSource(List.of(Map.of("qty", 7), Map.of("qty", 8))));
            params.put("SUB_PARAMS", new HashMap<>(Map.of("title", "From map")));
            JasperPrint print = fill("subreports_main.jrxml", params, c, new JREmptyDataSource(1));
            String text = text(print);
            assertThat(text).contains("Main report", "Via connection", "From map", "row 3", "row 5", "row 7", "row 8");
            // the limit of 2 applies to the connection run: the third row (qty 4) is not printed
            assertThat(text).doesNotContain("row 4");
            assertThat(text).contains("from_conn=3 from_ds=2");
        }
    }

    @Test
    void tableListBarcodesAndCrosstabRender() throws Exception {
        try (Connection c = h2()) {
            JasperPrint print = fill("components.jrxml", Map.of(), c, new JREmptyDataSource(1));
            String text = text(print);
            // table (minQty 4): pear 4 in group A, fig 5 in group B; the header cells and a column footer
            assertThat(text).contains("Item", "Numbers", "Qty", "Double", "Group A", "Group B", "pear", "fig", "8", "10", "end");
            assertThat(text).doesNotContain("apple" + "\n" + "3\n6");
            // list (Horizontal)
            assertThat(text).contains("list pear:4", "list fig:5");
            // crosstab: rows are the items, columns the groups, plus totals
            assertThat(text).contains("Item/Grp", "G A", "G B", "Total rows", "Total cols", "c3", "c4", "c5", "tt12");
            assertThat(text).doesNotContain("No crosstab data").doesNotContain("No rows");
            // two barcodes and a QR code were rendered as images
            assertThat(print.getPages().get(0).getElements().stream().filter(e -> e.getClass().getSimpleName().contains("Image")).count()).isGreaterThanOrEqualTo(3);
        }
    }

    // ---------------------------------------------------------------- helpers

    private static JasperDesign load(String jrxml) throws JRException {
        return JRXmlLoader.load(new ByteArrayInputStream(jrxml.getBytes(StandardCharsets.UTF_8)));
    }

    private static JasperPrint fill(String fixture, Map<String, Object> parameters, Connection connection, JRDataSource dataSource) throws Exception {
        JasperReport report = JasperCompileManager.compileReport(load(Jrxml6Fixtures.convert(fixture).jrxml()));
        Map<String, Object> params = new HashMap<>(parameters);
        if (dataSource != null) {
            if (connection != null) {
                params.put("REPORT_CONNECTION", connection);
            }
            return JasperFillManager.fillReport(report, params, dataSource);
        }
        return JasperFillManager.fillReport(report, params, connection);
    }

    private static String text(JasperPrint print) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        JRPdfExporter exporter = new JRPdfExporter();
        exporter.setExporterInput(new SimpleExporterInput(print));
        exporter.setExporterOutput(new SimpleOutputStreamExporterOutput(out));
        exporter.exportReport();
        try (PDDocument doc = Loader.loadPDF(out.toByteArray())) {
            return new PDFTextStripper().getText(doc).replace("\r", "");
        }
    }

    /** An H2 database with three rows: (A, apple, 3), (A, pear, 4), (B, fig, 5). */
    private static Connection h2() throws Exception {
        Connection c = DriverManager.getConnection("jdbc:h2:mem:jrxml_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        try (Statement s = c.createStatement()) {
            s.execute("create table items(grp varchar(10), item varchar(20), qty int)");
            s.execute("insert into items values ('A','apple',3),('A','pear',4),('B','fig',5)");
        }
        return c;
    }
}
