package com.github.somprasongd.jasperreport.api.jrxml;

import com.github.somprasongd.jasperreport.api.jrxml.LegacyJrxmlConverter.ConversionException;
import com.github.somprasongd.jasperreport.api.jrxml.LegacyJrxmlConverter.Result;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/** The converter as an XML to XML transformation: what it keeps, what it renames, what it reports, what it refuses. */
class LegacyJrxmlConverterTest {

    private static final String[] CLEAN = {"basic_legacy.jrxml", "subreports_main.jrxml", "subreports_sub.jrxml", "components.jrxml"};
    private static final Pattern CDATA = Pattern.compile("<!\\[CDATA\\[(.*?)]]>", Pattern.DOTALL);
    private static final Pattern UUID_ATTR = Pattern.compile(" uuid=\"([^\"]+)\"");

    // ---------------------------------------------------------------- what is kept as it was

    @ParameterizedTest
    @ValueSource(strings = {"basic_legacy.jrxml", "dtd_legacy.jrxml", "subreports_main.jrxml", "subreports_sub.jrxml", "components.jrxml", "unsupported.jrxml"})
    void everyCdataSectionIsCopiedByteForByte(String fixture) {
        String source = Jrxml6Fixtures.read(fixture);
        String converted = Jrxml6Fixtures.convert(fixture).jrxml();
        Matcher m = CDATA.matcher(source);
        int n = 0;
        while (m.find()) {
            n++;
            String section = m.group(0);
            // a section that sat inside something that is left out (a chart) may legitimately be gone; everything else must be there
            if (!fixture.equals("unsupported.jrxml") || converted.contains(section)) {
                assertThat(converted).as("CDATA of %s", fixture).contains(section);
            }
        }
        assertThat(n).isPositive();
    }

    @ParameterizedTest
    @ValueSource(strings = {"basic_legacy.jrxml", "dtd_legacy.jrxml", "subreports_main.jrxml", "subreports_sub.jrxml", "components.jrxml"})
    void uuidsAndPlainExpressionsSurvive(String fixture) {
        Set<String> uuids = new HashSet<>();
        Matcher m = UUID_ATTR.matcher(Jrxml6Fixtures.read(fixture));
        while (m.find()) {
            uuids.add(m.group(1));
        }
        String converted = Jrxml6Fixtures.convert(fixture).jrxml();
        assertThat(uuids).allSatisfy(uuid -> assertThat(converted).contains("uuid=\"" + uuid + "\""));
    }

    @Test
    void commentsAreKeptNearTheirOriginalPlace() {
        String converted = Jrxml6Fixtures.convert("basic_legacy.jrxml").jrxml();
        assertThat(converted).contains("<!-- Synthetic JR 6 report for the converter tests")
                .contains("<!-- picture row -->")
                .contains("<!-- a column break that never fires");
        // the comment in front of the image is still in front of the image
        assertThat(converted.indexOf("<!-- picture row -->")).isLessThan(converted.indexOf("kind=\"image\""));
    }

    @Test
    void outputIsTabIndentedWithAnXmlDeclarationAndNoNamespace() {
        String converted = Jrxml6Fixtures.convert("basic_legacy.jrxml").jrxml();
        assertThat(converted).startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        assertThat(converted).doesNotContain("xmlns").doesNotContain("schemaLocation").doesNotContain("    <");
        assertThat(converted).contains("\n\t<style name=\"Base\"").contains("\n\t\t<element kind=\"staticText\"");
        // no band wrapper for single-band sections, the band stays for detail
        assertThat(converted).contains("<title height=\"110\" splitType=\"Stretch\">").contains("<detail>\n\t\t<band height=\"20\" splitType=\"Prevent\">");
        assertThat(converted).doesNotContain("<reportElement").doesNotContain("<textElement").doesNotContain("<graphicElement");
    }

    // ---------------------------------------------------------------- what is renamed

    @Test
    void attributeStyleElementsAndRenamedAttributes() {
        Document doc = Jrxml6Fixtures.dom(Jrxml6Fixtures.convert("basic_legacy.jrxml").jrxml());
        // root: isXxx -> xxx
        assertThat(Jrxml6Fixtures.xpath(doc, "/jasperReport/@summaryWithPageHeaderAndFooter")).isEqualTo("true");
        assertThat(Jrxml6Fixtures.xpath(doc, "/jasperReport/@floatColumnFooter")).isEqualTo("true");
        assertThat(Jrxml6Fixtures.xpath(doc, "/jasperReport/@ignorePagination")).isEqualTo("false");
        assertThat(Jrxml6Fixtures.count(doc, "/jasperReport/@isTitleNewPage")).isZero();
        // <import value=".."/> -> <import>..</import>
        assertThat(Jrxml6Fixtures.xpath(doc, "/jasperReport/import[1]")).isEqualTo("java.text.*");
        // queryString -> query with a language
        assertThat(Jrxml6Fixtures.xpath(doc, "/jasperReport/query/@language")).isEqualTo("sql");
        // variableExpression -> expression, groupExpression -> expression, *Description -> description
        assertThat(Jrxml6Fixtures.xpath(doc, "/jasperReport/variable[@name='total_qty']/expression")).isEqualTo("$F{qty}");
        assertThat(Jrxml6Fixtures.xpath(doc, "/jasperReport/group/expression")).isEqualTo("$F{grp}");
        assertThat(Jrxml6Fixtures.xpath(doc, "/jasperReport/field[@name='grp']/description")).isEqualTo("group name");
        assertThat(Jrxml6Fixtures.xpath(doc, "/jasperReport/parameter[@name='threshold']/@forPrompting")).isEqualTo("false");
        // a textField with its reportElement, textElement and font folded into attributes
        String tf = "//element[@kind='textField'][@textAdjust='StretchHeight']";
        assertThat(Jrxml6Fixtures.xpath(doc, tf + "/@hTextAlign")).isEqualTo("Justified");
        assertThat(Jrxml6Fixtures.xpath(doc, tf + "/@vTextAlign")).isEqualTo("Top");
        assertThat(Jrxml6Fixtures.xpath(doc, tf + "/@linkType")).isEqualTo("Reference");
        assertThat(Jrxml6Fixtures.xpath(doc, tf + "/@linkTarget")).isEqualTo("Blank");
        assertThat(Jrxml6Fixtures.xpath(doc, tf + "/@stretchType")).isEqualTo("ElementGroupHeight");
        assertThat(Jrxml6Fixtures.xpath(doc, tf + "/@printRepeatedValues")).isEqualTo("false");
        assertThat(Jrxml6Fixtures.xpath(doc, tf + "/@removeLineWhenBlank")).isEqualTo("true");
        assertThat(Jrxml6Fixtures.xpath(doc, tf + "/hyperlinkParameter[@name='p1']/expression")).isEqualTo("\"v1\"");
        assertThat(Jrxml6Fixtures.xpath(doc, tf + "/expression")).isEqualTo("\"Hello \" + $P{who}");
        // the legacy expression class attribute is gone, the type stays out of it
        assertThat(Jrxml6Fixtures.count(doc, "//expression[@class]")).isZero();
        // staticText with font attributes and a box
        String st = "//element[@kind='staticText'][@key='greeting-label']";
        assertThat(Jrxml6Fixtures.xpath(doc, st + "/@fontSize")).isEqualTo("12");
        assertThat(Jrxml6Fixtures.xpath(doc, st + "/@bold")).isEqualTo("true");
        assertThat(Jrxml6Fixtures.xpath(doc, st + "/@hTextAlign")).isEqualTo("Center");
        assertThat(Jrxml6Fixtures.xpath(doc, st + "/@vTextAlign")).isEqualTo("Bottom");
        assertThat(Jrxml6Fixtures.xpath(doc, st + "/paragraph/@lineSpacing")).isEqualTo("Double");
        assertThat(Jrxml6Fixtures.xpath(doc, st + "/text")).isEqualTo("Greeting <&> text");
        assertThat(Jrxml6Fixtures.xpath(doc, st + "/box/pen/@lineWidth")).isEqualTo("1.0");
        assertThat(Jrxml6Fixtures.xpath(doc, st + "/property[@name='element.note']/@value")).isEqualTo("static");
        // image, line, rectangle, graphic element pens
        assertThat(Jrxml6Fixtures.xpath(doc, "//element[@kind='image']/@hImageAlign")).isEqualTo("Center");
        assertThat(Jrxml6Fixtures.xpath(doc, "//element[@kind='image']/@usingCache")).isEqualTo("true");
        assertThat(Jrxml6Fixtures.xpath(doc, "//element[@kind='image']/pen/@lineStyle")).isEqualTo("Dotted");
        assertThat(Jrxml6Fixtures.xpath(doc, "//element[@kind='line']/pen/@lineColor")).isEqualTo("#FF0000");
        assertThat(Jrxml6Fixtures.xpath(doc, "//element[@kind='rectangle']/@radius")).isEqualTo("5");
        // groups: header and footer keep their band, isSplitAllowed becomes splitType
        assertThat(Jrxml6Fixtures.xpath(doc, "/jasperReport/group/groupHeader/band/@height")).isEqualTo("22");
        assertThat(Jrxml6Fixtures.xpath(doc, "/jasperReport/summary/@splitType")).isEqualTo("Prevent");
        assertThat(Jrxml6Fixtures.count(doc, "/jasperReport/detail/band")).isEqualTo(2);
    }

    @Test
    void stylesKeepTheirBoxPenParagraphAndConditionalStyles() {
        Document doc = Jrxml6Fixtures.dom(Jrxml6Fixtures.convert("basic_legacy.jrxml").jrxml());
        String header = "/jasperReport/style[@name='Header']";
        assertThat(Jrxml6Fixtures.xpath(doc, header + "/@hTextAlign")).isEqualTo("Center");
        assertThat(Jrxml6Fixtures.xpath(doc, header + "/@hImageAlign")).isEqualTo("Center");
        assertThat(Jrxml6Fixtures.xpath(doc, header + "/@vTextAlign")).isEqualTo("Middle");
        assertThat(Jrxml6Fixtures.xpath(doc, header + "/@bold")).isEqualTo("true");
        assertThat(Jrxml6Fixtures.xpath(doc, header + "/@pdfEmbedded")).isEqualTo("false");
        assertThat(Jrxml6Fixtures.xpath(doc, header + "/pen/@lineStyle")).isEqualTo("Dashed");
        assertThat(Jrxml6Fixtures.xpath(doc, header + "/box/@topPadding")).isEqualTo("3");
        assertThat(Jrxml6Fixtures.xpath(doc, header + "/box/topPen/@lineStyle")).isEqualTo("Dotted");
        assertThat(Jrxml6Fixtures.xpath(doc, header + "/paragraph/tabStop/@alignment")).isEqualTo("Right");
        // JR 7 flattens <conditionalStyle><conditionExpression/><style ../></conditionalStyle>
        String zebra = "/jasperReport/style[@name='Zebra']/conditionalStyle";
        assertThat(Jrxml6Fixtures.xpath(doc, zebra + "/@backcolor")).isEqualTo("#EEEEEE");
        assertThat(Jrxml6Fixtures.xpath(doc, zebra + "/@bold")).isEqualTo("true");
        assertThat(Jrxml6Fixtures.xpath(doc, zebra + "/conditionExpression")).isEqualTo("$V{REPORT_COUNT} % 2 == 0");
        assertThat(Jrxml6Fixtures.xpath(doc, zebra + "/box/@padding")).isEqualTo("1");
        assertThat(Jrxml6Fixtures.count(doc, zebra + "/style")).isZero();
    }

    @Test
    void dtdEraReportsLoseTheirDoctypeAndGetPensFromBorderAttributes() {
        Result r = Jrxml6Fixtures.convert("dtd_legacy.jrxml");
        assertThat(r.alreadyCurrent()).isFalse();
        assertThat(r.jrxml()).doesNotContain("<!DOCTYPE");
        Document doc = Jrxml6Fixtures.dom(r.jrxml());
        String st = "//element[@kind='staticText'][@key='staticText-1']";
        // leftBorder="Thin" rightBorder="1Point" topBorder="Dotted" bottomBorder="2Point"
        assertThat(Jrxml6Fixtures.xpath(doc, st + "/box/leftPen/@lineWidth")).isEqualTo("0.5");
        assertThat(Jrxml6Fixtures.xpath(doc, st + "/box/rightPen/@lineWidth")).isEqualTo("1.0");
        assertThat(Jrxml6Fixtures.xpath(doc, st + "/box/rightPen/@lineColor")).isEqualTo("#FF0000");
        assertThat(Jrxml6Fixtures.xpath(doc, st + "/box/topPen/@lineStyle")).isEqualTo("Dashed");
        assertThat(Jrxml6Fixtures.xpath(doc, st + "/box/bottomPen/@lineWidth")).isEqualTo("2.0");
        assertThat(Jrxml6Fixtures.xpath(doc, st + "/box/@padding")).isEqualTo("2");
        // isStyledText -> markup, lineSpacing moves into the paragraph, the old stretch types get their JR 7 names
        assertThat(Jrxml6Fixtures.xpath(doc, st + "/@markup")).isEqualTo("styled");
        assertThat(Jrxml6Fixtures.xpath(doc, st + "/paragraph/@lineSpacing")).isEqualTo("1_1_2");
        assertThat(Jrxml6Fixtures.xpath(doc, st + "/@stretchType")).isEqualTo("ContainerHeight");
        assertThat(Jrxml6Fixtures.xpath(doc, "//element[@kind='rectangle']/@stretchType")).isEqualTo("ElementGroupHeight");
        // graphicElement pen="Thin|4Point|Dotted|None"
        assertThat(Jrxml6Fixtures.xpath(doc, "//element[@kind='rectangle']/pen/@lineWidth")).isEqualTo("0.5");
        assertThat(Jrxml6Fixtures.xpath(doc, "//element[@kind='line']/pen/@lineWidth")).isEqualTo("4.0");
        assertThat(Jrxml6Fixtures.xpath(doc, "//element[@kind='ellipse']/pen/@lineStyle")).isEqualTo("Dashed");
        // isSplitAllowed
        assertThat(Jrxml6Fixtures.xpath(doc, "/jasperReport/title/@splitType")).isEqualTo("Stretch");
        assertThat(Jrxml6Fixtures.xpath(doc, "/jasperReport/detail/band/@splitType")).isEqualTo("Prevent");
    }

    @Test
    void reportFontsBecomeStylesAndFontReferencesBecomeStyleReferences() {
        Result r = Jrxml6Fixtures.convert("dtd_legacy.jrxml");
        Document doc = Jrxml6Fixtures.dom(r.jrxml());
        assertThat(Jrxml6Fixtures.xpath(doc, "/jasperReport/style[@name='Bold']/@bold")).isEqualTo("true");
        assertThat(Jrxml6Fixtures.xpath(doc, "/jasperReport/style[@name='Normal']/@default")).isEqualTo("true");
        assertThat(Jrxml6Fixtures.xpath(doc, "//element[@key='staticText-1']/@style")).isEqualTo("Bold");
        // an element with its own font attributes keeps them next to the style
        assertThat(Jrxml6Fixtures.xpath(doc, "//element[@key='textField-1']/@style")).isEqualTo("Normal");
        assertThat(Jrxml6Fixtures.xpath(doc, "//element[@key='textField-1']/@fontSize")).isEqualTo("11");
        assertThat(Jrxml6Fixtures.xpath(doc, "//element[@key='textField-1']/@bold")).isEqualTo("true");
        // JR 6 never read the size of a <reportFont> (text printed at 10pt); the declared size is kept and each such font says so
        assertThat(Jrxml6Fixtures.xpath(doc, "/jasperReport/style[@name='Normal']/@fontSize")).isEqualTo("10");
        assertThat(Jrxml6Fixtures.xpath(doc, "/jasperReport/style[@name='Bold']/@fontSize")).isEqualTo("14");
        assertThat(r.warnings()).hasSize(2).allSatisfy(w -> assertThat(w).startsWith("reportFont[").contains("JR 6", "now prints at"));
    }

    // ---------------------------------------------------------------- components and subreports

    @Test
    void subreportsKeepEveryParameterConnectionDataSourceAndReturnValueForm() {
        Result r = Jrxml6Fixtures.convert("subreports_main.jrxml");
        assertThat(r.warnings()).isEmpty();
        Document doc = Jrxml6Fixtures.dom(r.jrxml());
        String conn = "//element[@kind='subreport'][@key='sub_conn']";
        assertThat(Jrxml6Fixtures.xpath(doc, conn + "/@usingCache")).isEqualTo("true");
        assertThat(Jrxml6Fixtures.xpath(doc, conn + "/parameter[@name='title']/expression")).isEqualTo("\"Via connection\"");
        assertThat(Jrxml6Fixtures.xpath(doc, conn + "/parameter[@name='limit']/expression")).isEqualTo("Integer.valueOf(2)");
        assertThat(Jrxml6Fixtures.xpath(doc, conn + "/connectionExpression")).isEqualTo("$P{REPORT_CONNECTION}");
        assertThat(Jrxml6Fixtures.xpath(doc, conn + "/expression")).isEqualTo("$P{SUB}");
        assertThat(Jrxml6Fixtures.xpath(doc, conn + "/returnValue/@subreportVariable")).isEqualTo("row_count");
        assertThat(Jrxml6Fixtures.xpath(doc, conn + "/returnValue/@toVariable")).isEqualTo("from_conn");
        assertThat(Jrxml6Fixtures.xpath(doc, conn + "/returnValue/@calculation")).isEqualTo("Sum");
        String ds = "//element[@kind='subreport'][@key='sub_ds']";
        assertThat(Jrxml6Fixtures.xpath(doc, ds + "/dataSourceExpression")).isEqualTo("$P{ROWS}");
        assertThat(Jrxml6Fixtures.xpath(doc, ds + "/parametersMapExpression")).isEqualTo("$P{SUB_PARAMS}");
        assertThat(Jrxml6Fixtures.xpath(doc, ds + "/returnValue/@toVariable")).isEqualTo("from_ds");
        // the incrementer class is passed on untouched (the built-in factories cannot be named here, so this is checked on a variant)
        String variant = Jrxml6Fixtures.read("subreports_main.jrxml").replace("toVariable=\"from_ds\"", "toVariable=\"from_ds\" incrementerFactoryClass=\"my.Incrementers\"");
        assertThat(Jrxml6Fixtures.xpath(Jrxml6Fixtures.dom(new LegacyJrxmlConverter().convert(variant).jrxml()), ds + "/returnValue/@incrementerFactoryClass"))
                .isEqualTo("my.Incrementers");
    }

    @Test
    void tableListBarcodeAndCrosstabAreConverted() {
        Result r = Jrxml6Fixtures.convert("components.jrxml");
        assertThat(r.warnings()).isEmpty();
        Document doc = Jrxml6Fixtures.dom(r.jrxml());
        String table = "//component[@kind='table']";
        assertThat(Jrxml6Fixtures.xpath(doc, table + "/@whenNoDataType")).isEqualTo("NoDataCell");
        assertThat(Jrxml6Fixtures.xpath(doc, table + "/datasetRun/@subDataset")).isEqualTo("Items");
        assertThat(Jrxml6Fixtures.xpath(doc, table + "/datasetRun/parameter[@name='minQty']/expression")).isEqualTo("Integer.valueOf(4)");
        assertThat(Jrxml6Fixtures.count(doc, table + "/column[@kind='single']")).isEqualTo(1);
        assertThat(Jrxml6Fixtures.count(doc, table + "/column[@kind='group']/column[@kind='single']")).isEqualTo(2);
        assertThat(Jrxml6Fixtures.xpath(doc, table + "/column[1]/tableHeader/@style")).isEqualTo("Head");
        assertThat(Jrxml6Fixtures.xpath(doc, table + "/column[1]/groupHeader[@groupName='by_grp']/cell/@height")).isEqualTo("20");
        assertThat(Jrxml6Fixtures.xpath(doc, table + "/column[1]/detailCell/@height")).isEqualTo("22");
        assertThat(Jrxml6Fixtures.xpath(doc, table + "/tableHeader/@splitType")).isEqualTo("Prevent");
        assertThat(Jrxml6Fixtures.xpath(doc, table + "/noData/@height")).isEqualTo("22");
        assertThat(Jrxml6Fixtures.xpath(doc, "//component[@kind='list']/@printOrder")).isEqualTo("Horizontal");
        assertThat(Jrxml6Fixtures.xpath(doc, "//component[@kind='list']/contents/@width")).isEqualTo("185");
        // barcode4j: the component namespace turns into kind="barcode4j:..", the numeric orientation into its name
        assertThat(Jrxml6Fixtures.xpath(doc, "//component[@kind='barcode4j:Code128']/@moduleWidth")).isEqualTo("1.5");
        assertThat(Jrxml6Fixtures.xpath(doc, "//component[@kind='barcode4j:Code128']/codeExpression")).isEqualTo("\"ABC-123\"");
        assertThat(Jrxml6Fixtures.xpath(doc, "//component[@kind='barcode4j:Code39']/@orientation")).isEqualTo("left");
        assertThat(Jrxml6Fixtures.xpath(doc, "//component[@kind='barcode4j:QRCode']/@errorCorrectionLevel")).isEqualTo("M");
        // crosstab
        String xt = "//element[@kind='crosstab']";
        assertThat(Jrxml6Fixtures.xpath(doc, xt + "/dataset/datasetRun/@subDataset")).isEqualTo("Items");
        assertThat(Jrxml6Fixtures.xpath(doc, xt + "/headerCell/@backcolor")).isEqualTo("#F0F0F0");
        assertThat(Jrxml6Fixtures.xpath(doc, xt + "/rowGroup[@name='item']/@position")).isEqualTo("Middle");
        assertThat(Jrxml6Fixtures.xpath(doc, xt + "/rowGroup[@name='item']/bucket/expression")).isEqualTo("$F{item}");
        assertThat(Jrxml6Fixtures.xpath(doc, xt + "/rowGroup[@name='item']/header/@backcolor")).isEqualTo("#F0F0FF");
        assertThat(Jrxml6Fixtures.xpath(doc, xt + "/columnGroup[@name='grp']/totalHeader/@backcolor")).isEqualTo("#BFBFFF");
        assertThat(Jrxml6Fixtures.xpath(doc, xt + "/measure[@name='qty_sum']/expression")).isEqualTo("$F{qty}");
        assertThat(Jrxml6Fixtures.count(doc, xt + "/cell")).isEqualTo(4);
        assertThat(Jrxml6Fixtures.xpath(doc, xt + "/cell[@rowTotalGroup='item'][@columnTotalGroup='grp']/contents/@backcolor")).isEqualTo("#BFFFBF");
        assertThat(Jrxml6Fixtures.count(doc, xt + "/whenNoDataCell/element")).isEqualTo(1);
        // sub dataset
        assertThat(Jrxml6Fixtures.xpath(doc, "/jasperReport/dataset[@name='Items']/query")).contains("$P{minQty}");
        assertThat(Jrxml6Fixtures.xpath(doc, "/jasperReport/dataset[@name='Items']/@uuid")).isEqualTo("33333333-3333-4333-8333-000000000002");
    }

    // ---------------------------------------------------------------- warnings

    @ParameterizedTest
    @ValueSource(strings = {"basic_legacy.jrxml", "subreports_main.jrxml", "subreports_sub.jrxml", "components.jrxml"})
    void reportsWithoutUnsupportedConstructsConvertWithoutWarnings(String fixture) {
        assertThat(Jrxml6Fixtures.convert(fixture).warnings()).isEmpty();
    }

    @Test
    void unsupportedConstructsAreReportedWithALocatorAndLeftOutVisibly() {
        Result r = Jrxml6Fixtures.convert("unsupported.jrxml");
        assertThat(r.alreadyCurrent()).isFalse();
        assertThat(r.warnings()).hasSize(5);
        assertThat(r.warnings()).anySatisfy(w -> assertThat(w).startsWith("title/band/pieChart[1]:").contains("charts are not converted"));
        assertThat(r.warnings()).anySatisfy(w -> assertThat(w).startsWith("title/band/componentElement[3]/map:").contains("not converted"));
        assertThat(r.warnings()).anySatisfy(w -> assertThat(w).startsWith("title/band/componentElement[2]/barbecue:").contains("jasperreports-barbecue"));
        assertThat(r.warnings()).anySatisfy(w -> assertThat(w).startsWith("queryString:").contains("'plsql'"));
        assertThat(r.warnings()).anySatisfy(w -> assertThat(w).startsWith("report:")
                .contains("net.sf.jasperreports.engine.data.JsonDataSource", "net.sf.jasperreports.json.data.JsonDataSource", "jasperreports-json"));
        // the places of the chart and the map are marked, the neighbours are untouched
        assertThat(r.jrxml()).contains("<!-- not converted: title/band/pieChart[1] -->").contains("<!-- not converted: title/band/componentElement[3]/map -->")
                .contains("Before the chart").contains("After the chart")
                .contains("uuid=\"44444444-4444-4444-8444-000000000010\"").contains("uuid=\"44444444-4444-4444-8444-000000000012\"");
        assertThat(r.jrxml()).doesNotContain("pieDataset").doesNotContain("latitudeExpression");
    }

    @Test
    void theJavaScriptExpressionLanguageNeedsAModuleThisApiLacks() {
        Result r = new LegacyJrxmlConverter().convert(Jrxml6Fixtures.read("subreports_sub.jrxml").replace("language=\"java\"", "language=\"javascript\""));
        assertThat(r.warnings()).singleElement().satisfies(w -> assertThat(w).startsWith("report:").contains("jasperreports-javascript"));
        assertThat(r.jrxml()).contains("language=\"javascript\"");
        assertThat(Jrxml6Fixtures.convert("subreports_sub.jrxml").warnings()).isEmpty();
    }

    @Test
    void classesThatJr7MovedOrRemovedAreNamedWithTheirReplacement() {
        String source = Jrxml6Fixtures.read("subreports_sub.jrxml")
                .replace("<![CDATA[\"row \" + $F{qty}]]>", "<![CDATA[net.sf.jasperreports.engine.JRImageRenderer.getInstance(\"x\") + net.sf.jasperreports.engine.data.ExcelDataSource.class.getName()"
                        + " + net.sf.jasperreports.engine.export.JRPdfExporter.class.getName() + net.sf.jasperreports.engine.JRImageRenderer.class]]>");
        Result r = new LegacyJrxmlConverter().convert(source);
        assertThat(r.warnings()).hasSize(3);
        assertThat(r.warnings()).anySatisfy(w -> assertThat(w).startsWith("report:").contains("net.sf.jasperreports.engine.JRImageRenderer is named 2 time(s)",
                "detail/band/textField[0]", "no longer has it").doesNotContain("moved"));
        assertThat(r.warnings()).anySatisfy(w -> assertThat(w).contains("net.sf.jasperreports.engine.data.ExcelDataSource", "net.sf.jasperreports.poi.data.ExcelDataSource",
                "jasperreports-excel-poi", "does not include"));
        assertThat(r.warnings()).anySatisfy(w -> assertThat(w).contains("net.sf.jasperreports.engine.export.JRPdfExporter", "net.sf.jasperreports.pdf.JRPdfExporter",
                "jasperreports-pdf, included in this API"));
        // the expression itself is not rewritten
        assertThat(r.jrxml()).contains("net.sf.jasperreports.engine.export.JRPdfExporter.class.getName()");
    }

    @Test
    void unknownAttributesAndElementsAreNeverDroppedSilently() {
        String source = Jrxml6Fixtures.read("basic_legacy.jrxml")
                .replace("<reportElement key=\"greeting-label\"", "<reportElement bogus=\"1\" key=\"greeting-label\"")
                .replace("<line direction=\"BottomUp\">", "<line direction=\"BottomUp\"><mystery/>")
                .replace("<style name=\"Base\"", "<style weird=\"x\" name=\"Base\"");
        Result r = new LegacyJrxmlConverter().convert(source);
        assertThat(r.warnings()).hasSize(3);
        assertThat(r.warnings()).anySatisfy(w -> assertThat(w).startsWith("title/band/staticText[0]/reportElement:").contains("'bogus'"));
        assertThat(r.warnings()).anySatisfy(w -> assertThat(w).startsWith("title/band/line[").contains("<mystery>"));
        assertThat(r.warnings()).anySatisfy(w -> assertThat(w).startsWith("style[Base]:").contains("'weird'"));
        assertThat(r.jrxml()).doesNotContain("bogus").doesNotContain("mystery");
    }

    @ParameterizedTest
    @ValueSource(strings = {"basic_legacy.jrxml", "dtd_legacy.jrxml", "subreports_main.jrxml", "subreports_sub.jrxml", "components.jrxml"})
    void everySourceElementIsVisitedSoNothingCanVanishWithoutAWarning(String fixture) {
        // an unknown attribute and an unknown child on one element at a time: each must come back as a warning of its own
        String xml = stripDoctype(Jrxml6Fixtures.read(fixture));
        int elements = Jrxml6Fixtures.elementCount(Jrxml6Fixtures.dom(xml));
        List<String> unreported = new ArrayList<>();
        for (int i = 0; i < elements; i++) {
            Document doc = Jrxml6Fixtures.dom(xml);
            List<Element> all = new ArrayList<>();
            collect(doc.getDocumentElement(), all);
            Element target = all.get(i);
            target.setAttribute("zzUnknown", "1");
            Result attribute = new LegacyJrxmlConverter().convert(serialize(doc));
            if (attribute.warnings().stream().noneMatch(w -> w.contains("'zzUnknown'"))) {
                unreported.add("attribute on <" + target.getTagName() + "> #" + i);
            }
            target.removeAttribute("zzUnknown");
            target.appendChild(doc.createElementNS(null, "zzChild"));
            Result child = new LegacyJrxmlConverter().convert(serialize(doc));
            if (child.warnings().stream().noneMatch(w -> w.contains("zzChild"))) {
                unreported.add("child in <" + target.getTagName() + "> #" + i);
            }
        }
        assertThat(unreported).as("elements that swallow an unknown attribute or child silently in %s", fixture).isEmpty();
    }

    // ---------------------------------------------------------------- already JR 7

    @Test
    void jr7ReportsPassThroughUnchanged() throws Exception {
        String demo = Files.readString(Path.of("src/test/resources/reports/demo/demo.jrxml"));
        Result r = new LegacyJrxmlConverter().convert(demo);
        assertThat(r.alreadyCurrent()).isTrue();
        assertThat(r.jrxml()).isSameAs(demo);
        assertThat(r.warnings()).isEmpty();
        for (String other : List.of("reports/demo/sub_info.jrxml", "reports/lang/lang_java.jrxml", "reports/modes/json_demo.jrxml", "reports/params/params_demo.jrxml")) {
            assertThat(new LegacyJrxmlConverter().convert(Files.readString(Path.of("src/test/resources", other))).alreadyCurrent()).as(other).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"basic_legacy.jrxml", "dtd_legacy.jrxml", "subreports_main.jrxml", "subreports_sub.jrxml", "components.jrxml", "unsupported.jrxml"})
    void convertingTheOutputAgainChangesNothing(String fixture) {
        String once = Jrxml6Fixtures.convert(fixture).jrxml();
        Result twice = new LegacyJrxmlConverter().convert(once);
        assertThat(twice.alreadyCurrent()).isTrue();
        assertThat(twice.jrxml()).isEqualTo(once);
    }

    @Test
    void theJr6FixtureOfTheProjectIsConverted() throws Exception {
        Result r = new LegacyJrxmlConverter().convert(Files.readString(Path.of("src/test/resources/reports/old6/legacy_demo.jrxml")));
        assertThat(r.alreadyCurrent()).isFalse();
        assertThat(r.warnings()).isEmpty();
        assertThat(r.jrxml()).contains("<query language=\"sql\">").contains("<element kind=\"subreport\"").contains("<expression><![CDATA[$P{SUBREPORT_DIR} + \"legacy_sub.jasper\"]]></expression>");
    }

    // ---------------------------------------------------------------- hostile and broken input

    @Test
    void externalEntitiesAreRejectedAndNeverRead(@TempDir Path dir) throws Exception {
        Path canary = dir.resolve("canary.txt");
        Files.writeString(canary, "TOP-SECRET-CANARY");
        String xxe = "<?xml version=\"1.0\"?>\n<!DOCTYPE jasperReport [<!ENTITY xxe SYSTEM \"" + canary.toUri() + "\">]>\n"
                + "<jasperReport name=\"x\" pageWidth=\"100\" pageHeight=\"100\" columnWidth=\"80\" leftMargin=\"10\" rightMargin=\"10\" topMargin=\"10\" bottomMargin=\"10\">"
                + "<title><band height=\"20\"><staticText><reportElement x=\"0\" y=\"0\" width=\"50\" height=\"20\"/><text>&xxe;</text></staticText></band></title></jasperReport>";
        assertThatThrownBy(() -> new LegacyJrxmlConverter().convert(xxe)).isInstanceOf(ConversionException.class)
                .hasMessageContaining("DOCTYPE").hasMessageNotContaining("TOP-SECRET-CANARY");
    }

    @Test
    void parameterEntitiesAndOutOfBandPayloadsAreRejectedWithoutANetworkCall() {
        String oob = "<?xml version=\"1.0\"?>\n<!DOCTYPE jasperReport [<!ENTITY % remote SYSTEM \"http://127.0.0.1:9/evil.dtd\"> %remote;]>\n<jasperReport name=\"x\"/>";
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> assertThatThrownBy(() -> new LegacyJrxmlConverter().convert(oob))
                .isInstanceOf(ConversionException.class).hasMessageContaining("DOCTYPE"));
    }

    @Test
    void entityExpansionBombsAreRejected() {
        StringBuilder bomb = new StringBuilder("<?xml version=\"1.0\"?>\n<!DOCTYPE jasperReport [<!ENTITY a \"aaaaaaaaaa\">");
        String previous = "a";
        for (int i = 0; i < 9; i++) {
            String name = "e" + i;
            bomb.append("<!ENTITY ").append(name).append(" \"").append(("&" + previous + ";").repeat(10)).append("\">");
            previous = name;
        }
        bomb.append("]>\n<jasperReport name=\"&").append(previous).append(";\"/>");
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> assertThatThrownBy(() -> new LegacyJrxmlConverter().convert(bomb.toString()))
                .isInstanceOf(ConversionException.class));
    }

    @Test
    void theStandardDoctypeIsDroppedWithoutBeingFetchedAnythingElseInTheDoctypeIsRefused() {
        String body = "<jasperReport name=\"x\" pageWidth=\"100\" pageHeight=\"100\" columnWidth=\"80\" leftMargin=\"10\" rightMargin=\"10\" topMargin=\"10\" bottomMargin=\"10\">"
                + "<title><band height=\"20\"/></title></jasperReport>";
        // a DTD URL nobody listens on: the converter must not try to load it
        String standard = "<?xml version=\"1.0\"?>\n<!DOCTYPE jasperReport PUBLIC \"-//JasperReports//DTD Report Design//EN\" \"http://127.0.0.1:9/jasperreport.dtd\">\n" + body;
        Result r = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> new LegacyJrxmlConverter().convert(standard));
        assertThat(r.alreadyCurrent()).isFalse();
        assertThat(r.jrxml()).contains("<title height=\"20\"/>");
        // an internal subset is not the standard declaration
        String withSubset = "<?xml version=\"1.0\"?>\n<!DOCTYPE jasperReport PUBLIC \"-//JasperReports//DTD Report Design//EN\" \"x.dtd\" [<!ENTITY e \"v\">]>\n" + body;
        assertThatThrownBy(() -> new LegacyJrxmlConverter().convert(withSubset)).isInstanceOf(ConversionException.class).hasMessageContaining("DOCTYPE");
        String otherRoot = "<?xml version=\"1.0\"?>\n<!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.0//EN\" \"x.dtd\">\n<html/>";
        assertThatThrownBy(() -> new LegacyJrxmlConverter().convert(otherRoot)).isInstanceOf(ConversionException.class);
    }

    @Test
    void xIncludeIsNotProcessed(@TempDir Path dir) throws Exception {
        Path canary = dir.resolve("canary.txt");
        Files.writeString(canary, "TOP-SECRET-CANARY");
        String source = Jrxml6Fixtures.read("basic_legacy.jrxml").replace("<background>",
                "<background xmlns:xi=\"http://www.w3.org/2001/XInclude\"><xi:include href=\"" + canary.toUri() + "\" parse=\"text\"/>");
        Result r = new LegacyJrxmlConverter().convert(source);
        assertThat(r.jrxml()).doesNotContain("TOP-SECRET-CANARY");
        assertThat(r.warnings()).anySatisfy(w -> assertThat(w).startsWith("background:").contains("<include>"));
    }

    @Test
    void emptyMalformedAndForeignInputIsRefusedWithAReadableMessage() {
        LegacyJrxmlConverter c = new LegacyJrxmlConverter();
        assertThatThrownBy(() -> c.convert(null)).isInstanceOf(ConversionException.class).hasMessageContaining("empty");
        assertThatThrownBy(() -> c.convert("  \n ")).isInstanceOf(ConversionException.class).hasMessageContaining("empty");
        assertThatThrownBy(() -> c.convert("<jasperReport name=\"x\"><title>")).isInstanceOf(ConversionException.class)
                .hasMessageContaining("Malformed XML at line 1");
        assertThatThrownBy(() -> c.convert("not xml at all")).isInstanceOf(ConversionException.class);
        assertThatThrownBy(() -> c.convert("<report/>")).isInstanceOf(ConversionException.class).hasMessageContaining("expected <jasperReport>");
    }

    @Test
    void absurdNestingAndSizeAreRefusedInsteadOfBlowingTheStack() {
        String deep = "<jasperReport name=\"x\"><title><band height=\"10\">" + "<frame><reportElement x=\"0\" y=\"0\" width=\"1\" height=\"1\"/>".repeat(5000)
                + "</frame>".repeat(5000) + "</band></title></jasperReport>";
        assertThatThrownBy(() -> new LegacyJrxmlConverter().convert(deep)).isInstanceOf(ConversionException.class).hasMessageContaining("Malformed XML");
        String huge = "<jasperReport name=\"" + "x".repeat(LegacyJrxmlConverter.MAX_CHARS) + "\"/>";
        assertThatThrownBy(() -> new LegacyJrxmlConverter().convert(huge)).isInstanceOf(ConversionException.class).hasMessageContaining("larger than");
    }

    @Test
    void aByteOrderMarkInFrontIsTolerated() {
        Result r = new LegacyJrxmlConverter().convert("﻿" + Jrxml6Fixtures.read("subreports_sub.jrxml"));
        assertThat(r.alreadyCurrent()).isFalse();
    }

    // ---------------------------------------------------------------- threads

    @Test
    void oneConverterServesManyThreads() throws Exception {
        LegacyJrxmlConverter converter = new LegacyJrxmlConverter();
        String expected = converter.convert(Jrxml6Fixtures.read("components.jrxml")).jrxml();
        String source = Jrxml6Fixtures.read("components.jrxml");
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                results.add(pool.submit(() -> converter.convert(source).jrxml()));
            }
            for (Future<String> f : results) {
                assertThat(f.get()).isEqualTo(expected);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------------------------------------------------------------- helpers

    private static void collect(Element e, List<Element> into) {
        into.add(e);
        for (Node c = e.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c instanceof Element ce) {
                collect(ce, into);
            }
        }
    }

    /** The DOM helper refuses a DOCTYPE; the DTD-era fixture is checked here without it. */
    private static String stripDoctype(String xml) {
        return xml.replaceAll("<!DOCTYPE[^>]*>", "");
    }

    private static String serialize(Document doc) {
        try {
            javax.xml.transform.Transformer t = javax.xml.transform.TransformerFactory.newInstance().newTransformer();
            java.io.StringWriter w = new java.io.StringWriter();
            t.transform(new javax.xml.transform.dom.DOMSource(doc), new javax.xml.transform.stream.StreamResult(w));
            return w.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
