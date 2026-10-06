package com.github.somprasongd.jasperreport.api.jrxml;

import org.w3c.dom.Comment;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts a JasperReports 6.x JRXML (the nested {@code <reportElement>}/{@code <band>} syntax, with or without the
 * old DTD) to the attribute style of JasperReports 7 ({@code <element kind="textField" ...>}), so existing reports can
 * be migrated without opening each one in Jaspersoft Studio 7.
 *
 * <p>It is an XML to XML transformation on the JDK's own APIs, driven by what JR 6.21's {@code JRXmlDigester} reads and
 * what JR 7.0.8's Jackson mapping accepts. Nothing is dropped silently: every attribute or element that has no JR 7
 * counterpart, and every construct the converter does not know (charts, maps, ...), ends up in
 * {@link Result#warnings()} with a locator such as {@code title/band/crosstab[2]}; an unconvertible element is replaced
 * by an XML comment so its place in the band stays visible. Expression text is copied as is (CDATA stays CDATA).
 *
 * <p>Stateless and thread-safe; the input is parsed with DOCTYPE and external entities disabled.
 */
public final class LegacyJrxmlConverter {

    /**
     * @param jrxml          the JR 7 report, tab-indented with an XML declaration; the input itself when it already is JR 7
     * @param warnings       what could not be converted or needs a human look, as {@code "locator: message"}
     * @param alreadyCurrent {@code true} when the input already used the JR 7 syntax and was returned unchanged
     */
    public record Result(String jrxml, List<String> warnings, boolean alreadyCurrent) {
    }

    /** The input is not a JRXML report this converter can read (empty, malformed XML, a DOCTYPE, another root). */
    public static class ConversionException extends RuntimeException {
        public ConversionException(String message) {
            super(message);
        }

        public ConversionException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    static final String COMPONENTS_NS = "http://jasperreports.sourceforge.net/jasperreports/components";

    /** The DOCTYPE of old reports, with or without the leading dash of the public id some editors left out; no internal subset. */
    private static final Pattern STANDARD_DOCTYPE = Pattern.compile(
            "<!DOCTYPE\\s+jasperReport\\s+(?:PUBLIC\\s+(?:\"[^\"]*\"|'[^']*')\\s+(?:\"[^\"]*\"|'[^']*')|SYSTEM\\s+(?:\"[^\"]*\"|'[^']*'))\\s*>");

    /** Element names that only exist in the JR 6 syntax (JR 7 has {@code <element kind=...>} for all of them). */
    private static final Set<String> LEGACY_ELEMENTS = Set.of("reportElement", "queryString", "subDataset", "textField",
            "staticText", "image", "line", "rectangle", "ellipse", "break", "frame", "subreport", "textElement", "graphicElement",
            "reportFont", "componentElement", "textFieldExpression", "imageExpression", "subreportExpression", "variableExpression",
            "groupExpression", "parameterDescription", "fieldDescription", "scriptletDescription", "subreportParameter",
            "datasetParameter", "genericElement", "crosstabCell", "crosstabDataset");

    private static final Pattern JR_CLASS = Pattern.compile("net\\.sf\\.jasperreports(?:\\.[A-Za-z_][A-Za-z0-9_]*)+");

    private static final Set<String> LEGACY_ROOT_ATTRIBUTES = Set.of("isTitleNewPage", "isSummaryNewPage",
            "isSummaryWithPageHeaderAndFooter", "isFloatColumnFooter", "isIgnorePagination");

    /** Reports are a few hundred KB; the whole document is held as a DOM, so what is far beyond that is refused. */
    public static final int MAX_CHARS = 16_000_000;

    /** The deepest element nesting accepted (a crosstab inside a frame inside a table cell is still far below). */
    private static final int MAX_DEPTH = 200;

    public Result convert(String jrxml) {
        if (jrxml == null || jrxml.isBlank()) {
            throw new ConversionException("The JRXML is empty");
        }
        if (jrxml.length() > MAX_CHARS) {
            throw new ConversionException("The JRXML is larger than " + MAX_CHARS + " characters");
        }
        Document doc = parse(withoutStandardDoctype(jrxml));
        Element root = doc.getDocumentElement();
        if (!"jasperReport".equals(localName(root))) {
            throw new ConversionException("The root element is <" + localName(root) + ">, expected <jasperReport>");
        }
        if (!isLegacy(root)) {
            return new Result(jrxml, List.of(), true);
        }
        Run run = new Run();
        Out report = run.report(root);
        for (Node n = doc.getFirstChild(); n != null && n != root; n = n.getNextSibling()) {
            if (n instanceof Comment c) {
                report.leading.add(c.getData());
            }
        }
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        report.print(sb, 0);
        return new Result(sb.toString(), List.copyOf(run.warnings), false);
    }

    // ---------------------------------------------------------------- parsing

    /** The DOCTYPE of DTD-era reports is dropped textually (it is never fetched); the parser then refuses any other DOCTYPE. */
    private static String withoutStandardDoctype(String s) {
        if (s.startsWith("\uFEFF")) {
            s = s.substring(1);
        }
        int start = s.indexOf("<jasperReport");
        String prolog = start < 0 ? s : s.substring(0, start);
        Matcher m = STANDARD_DOCTYPE.matcher(prolog);
        return m.find() ? prolog.substring(0, m.start()) + prolog.substring(m.end()) + (start < 0 ? "" : s.substring(start)) : s;
    }

    private static Document parse(String xml) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            f.setCoalescing(false);
            f.setIgnoringComments(false);
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            f.setAttribute("jdk.xml.maxElementDepth", String.valueOf(MAX_DEPTH));
            DocumentBuilder b = f.newDocumentBuilder();
            b.setEntityResolver((publicId, systemId) -> {
                throw new SAXException("External entities are not allowed");
            });
            b.setErrorHandler(null);
            return b.parse(new InputSource(new StringReader(xml)));
        } catch (SAXParseException e) {
            String message = e.getMessage() == null ? "" : e.getMessage();
            if (message.contains("DOCTYPE")) {
                throw new ConversionException("A DOCTYPE declaration is not allowed (only the standard JasperReports one is accepted)", e);
            }
            throw new ConversionException("Malformed XML at line " + e.getLineNumber() + ", column " + e.getColumnNumber() + ": " + message, e);
        } catch (SAXException | IOException | ParserConfigurationException e) {
            throw new ConversionException("The JRXML cannot be read as XML: " + e.getMessage(), e);
        }
    }

    private static boolean isLegacy(Element root) {
        if ("http://jasperreports.sourceforge.net/jasperreports".equals(root.getNamespaceURI())) {
            return true;
        }
        NamedNodeMap attrs = root.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            if (LEGACY_ROOT_ATTRIBUTES.contains(attrs.item(i).getNodeName())) {
                return true;
            }
        }
        return hasLegacyElement(root);
    }

    /** Sections whose band is folded into the section in JR 7 (only detail and the group sections keep a {@code <band>}). */
    private static final Set<String> SINGLE_BAND_SECTIONS = Set.of("background", "title", "pageHeader", "columnHeader", "columnFooter", "pageFooter",
            "lastPageFooter", "summary", "noData");

    private static boolean hasLegacyElement(Element e) {
        for (Node c = e.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c instanceof Element ce && (LEGACY_ELEMENTS.contains(localName(ce))
                    || localName(ce).equals("band") && SINGLE_BAND_SECTIONS.contains(localName(e)) || hasLegacyElement(ce))) {
                return true;
            }
        }
        return false;
    }

    static String localName(Node n) {
        String l = n.getLocalName();
        if (l != null) {
            return l;
        }
        String name = n.getNodeName();
        int colon = name.indexOf(':');
        return colon < 0 ? name : name.substring(colon + 1);
    }

    // ---------------------------------------------------------------- output tree

    /** A text piece of an element: plain text (escaped when printed) or a CDATA section (printed verbatim). */
    private record Seg(String text, boolean cdata) {
    }

    /** An output element; attributes and children are put in the order JR 7's own writer uses by {@link #sort}. */
    private static final class Out {
        final String name;
        final Map<String, String> attrs = new LinkedHashMap<>();
        final List<Out> kids = new ArrayList<>();
        List<Seg> text;
        final List<String> leading = new ArrayList<>();
        final List<String> trailing = new ArrayList<>();
        String comment;

        Out(String name) {
            this.name = name;
        }

        static Out comment(String text) {
            Out o = new Out(null);
            o.comment = text;
            return o;
        }

        Out set(String name, String value) {
            if (value != null) {
                attrs.put(name, value);
            }
            return this;
        }

        Out add(Out kid) {
            if (kid != null) {
                kids.add(kid);
            }
            return this;
        }

        boolean isEmpty() {
            return attrs.isEmpty() && kids.isEmpty() && text == null;
        }

        /** Reorders attributes and children by {@code order} (names not listed keep their relative order, last). */
        Out sort(List<String> order) {
            Map<String, Integer> idx = new HashMap<>();
            for (int i = 0; i < order.size(); i++) {
                idx.put(order.get(i), i);
            }
            List<Map.Entry<String, String>> entries = new ArrayList<>(attrs.entrySet());
            entries.sort((a, b) -> Integer.compare(idx.getOrDefault(a.getKey(), 9999), idx.getOrDefault(b.getKey(), 9999)));
            attrs.clear();
            entries.forEach(e -> attrs.put(e.getKey(), e.getValue()));
            // a comment standing in for an element that was left out keeps the place of the elements
            kids.sort((a, b) -> Integer.compare(idx.getOrDefault(a.name == null ? "element" : a.name, 9999),
                    idx.getOrDefault(b.name == null ? "element" : b.name, 9999)));
            return this;
        }

        void print(StringBuilder sb, int depth) {
            for (String c : leading) {
                indent(sb, depth).append("<!--").append(c).append("-->\n");
            }
            if (name == null) {
                indent(sb, depth).append("<!--").append(comment).append("-->\n");
                return;
            }
            indent(sb, depth).append('<').append(name);
            attrs.forEach((k, v) -> sb.append(' ').append(k).append("=\"").append(escapeAttr(v)).append('"'));
            if (text != null && !text.isEmpty()) {
                sb.append('>');
                for (Seg s : text) {
                    sb.append(s.cdata() ? "<![CDATA[" + s.text() + "]]>" : escapeText(s.text()));
                }
                sb.append("</").append(name).append(">\n");
            } else if (kids.isEmpty() && trailing.isEmpty()) {
                sb.append("/>\n");
            } else {
                sb.append(">\n");
                for (Out k : kids) {
                    k.print(sb, depth + 1);
                }
                for (String c : trailing) {
                    indent(sb, depth + 1).append("<!--").append(c).append("-->\n");
                }
                indent(sb, depth).append("</").append(name).append(">\n");
            }
        }

        private static StringBuilder indent(StringBuilder sb, int depth) {
            for (int i = 0; i < depth; i++) {
                sb.append('\t');
            }
            return sb;
        }

        private static String escapeAttr(String v) {
            StringBuilder sb = new StringBuilder(v.length() + 8);
            for (char c : v.toCharArray()) {
                switch (c) {
                    case '&' -> sb.append("&amp;");
                    case '<' -> sb.append("&lt;");
                    case '>' -> sb.append("&gt;");
                    case '"' -> sb.append("&quot;");
                    case '\n' -> sb.append("&#10;");
                    case '\r' -> sb.append("&#13;");
                    case '\t' -> sb.append("&#9;");
                    default -> sb.append(c);
                }
            }
            return sb.toString();
        }

        private static String escapeText(String v) {
            return v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }
    }

    private static List<String> order(String... names) {
        return List.of(names);
    }

    private static List<String> concat(List<String> a, String... b) {
        List<String> r = new ArrayList<>(a);
        r.addAll(List.of(b));
        return r;
    }

    private static final List<String> COMMON = order("kind", "uuid", "key", "x", "y", "width", "height", "forecolor", "backcolor",
            "mode", "positionType", "stretchType", "printRepeatedValues", "printInFirstWholeBand", "printWhenDetailOverflows",
            "printWhenGroupChanges", "removeLineWhenBlank");
    private static final List<String> TAIL = order("style", "property", "propertyExpression", "styleExpression", "printWhenExpression");
    private static final List<String> TEXT_FIELD = concat(COMMON, "markup", "fontName", "fontSize", "pdfFontName", "rotation", "textAdjust",
            "evaluationTime", "linkType", "linkTarget", "pattern", "bold", "evaluationGroup", "bookmarkLevel", "blankWhenNull", "italic",
            "strikeThrough", "underline", "pdfEncoding", "pdfEmbedded", "hTextAlign", "vTextAlign", "style", "property", "propertyExpression",
            "styleExpression", "printWhenExpression", "box", "paragraph", "expression", "patternExpression", "anchorNameExpression",
            "bookmarkLevelExpression", "hyperlinkReferenceExpression", "hyperlinkWhenExpression", "hyperlinkAnchorExpression",
            "hyperlinkPageExpression", "hyperlinkTooltipExpression", "hyperlinkParameter");
    private static final List<String> STATIC_TEXT = concat(COMMON, "markup", "fontName", "fontSize", "pdfFontName", "bold", "italic",
            "pdfEmbedded", "rotation", "strikeThrough", "underline", "pdfEncoding", "hTextAlign", "vTextAlign", "style", "property",
            "propertyExpression", "styleExpression", "printWhenExpression", "box", "paragraph", "text");
    private static final List<String> IMAGE = concat(concat(COMMON, "fill", "scaleImage", "rotation", "hImageAlign", "vImageAlign", "evaluationTime",
            "evaluationGroup", "linkType", "linkTarget", "usingCache", "lazy", "onErrorType", "bookmarkLevel"), concat(TAIL, "box", "pen", "expression")
            .toArray(String[]::new));
    private static final List<String> LINE = concat(concat(COMMON, "fill", "direction"), concat(TAIL, "pen").toArray(String[]::new));
    private static final List<String> RECTANGLE = concat(concat(COMMON, "fill", "radius"), concat(TAIL, "pen").toArray(String[]::new));
    private static final List<String> ELLIPSE = concat(concat(COMMON, "fill"), concat(TAIL, "pen").toArray(String[]::new));
    private static final List<String> BREAK = concat(concat(COMMON, "type"), TAIL.toArray(String[]::new));
    private static final List<String> FRAME = concat(concat(COMMON, "borderSplitType"), concat(TAIL, "box", "element").toArray(String[]::new));
    private static final List<String> SUBREPORT = concat(concat(COMMON, "usingCache", "runToBottom", "overflowType"), concat(TAIL,
            "parametersMapExpression", "parameter", "connectionExpression", "dataSourceExpression", "expression", "returnValue").toArray(String[]::new));
    private static final List<String> COMPONENT = concat(concat(COMMON), concat(TAIL, "component").toArray(String[]::new));
    private static final List<String> GENERIC = concat(concat(COMMON, "evaluationTime", "evaluationGroup"), concat(TAIL, "genericType", "parameter")
            .toArray(String[]::new));
    private static final List<String> CROSSTAB = concat(concat(COMMON, "columnBreakOffset", "repeatColumnHeaders", "repeatRowHeaders", "runDirection",
            "ignoreWidth", "horizontalPosition"), concat(TAIL, "box", "dataset", "parametersMapExpression", "parameter", "headerCell", "titleCell",
            "rowGroup", "columnGroup", "measure", "cell", "whenNoDataCell").toArray(String[]::new));
    private static final List<String> REPORT = order("name", "language", "columnCount", "printOrder", "columnDirection", "pageWidth", "pageHeight",
            "orientation", "whenNoDataType", "sectionType", "columnWidth", "columnSpacing", "leftMargin", "rightMargin", "topMargin", "bottomMargin",
            "titleNewPage", "summaryNewPage", "summaryWithPageHeaderAndFooter", "floatColumnFooter", "scriptletClass", "formatFactoryClass",
            "resourceBundle", "whenResourceMissingType", "ignorePagination", "uuid", "property", "propertyExpression", "import", "template", "style",
            "dataset", "scriptlet", "parameter", "query", "field", "sortField", "variable", "filterExpression", "group", "background", "title",
            "pageHeader", "columnHeader", "detail", "columnFooter", "pageFooter", "lastPageFooter", "summary", "noData");
    private static final List<String> DATASET = order("name", "scriptletClass", "resourceBundle", "whenResourceMissingType", "uuid", "property",
            "propertyExpression", "scriptlet", "parameter", "query", "field", "sortField", "variable", "filterExpression", "group");
    private static final List<String> STYLE = order("name", "default", "style", "mode", "forecolor", "backcolor", "fill", "radius", "scaleImage",
            "hTextAlign", "vTextAlign", "hImageAlign", "vImageAlign", "rotation", "markup", "pattern", "blankWhenNull", "fontName", "fontSize", "bold",
            "italic", "underline", "strikeThrough", "pdfFontName", "pdfEncoding", "pdfEmbedded", "conditionExpression", "pen", "box", "paragraph",
            "conditionalStyle");
    private static final List<String> BAND = order("height", "splitType", "property", "propertyExpression", "printWhenExpression", "returnValue",
            "element");

    // ---------------------------------------------------------------- the conversion

    /** Everything one conversion needs; a new one per call keeps the converter stateless. */
    private static final class Run {

        final List<String> warnings = new ArrayList<>();
        /** JasperReports classes that JR 7 moved, by the old name: how often they are named and where first. */
        final Map<String, ClassRef> classRefs = new LinkedHashMap<>();

        record ClassRef(RelocatedClasses.Moved moved, String firstPath, int[] count) {
        }

        /** The locator of a warning: the element path below {@code <jasperReport>}, e.g. {@code detail/band[1]/textField[3]}. */
        static String locator(String path) {
            return path.equals("jasperReport") ? "report" : path.startsWith("jasperReport/") ? path.substring("jasperReport/".length()) : path;
        }

        void warn(String path, String message) {
            warnings.add(locator(path) + ": " + message);
        }

        /** Notes the JasperReports classes whose package JR 7 changed that {@code text} names. */
        void scanClasses(String text, String path) {
            if (text == null || !text.contains("net.sf.jasperreports.")) {
                return;
            }
            Matcher m = JR_CLASS.matcher(text);
            while (m.find()) {
                RelocatedClasses.Moved moved = RelocatedClasses.find(m.group());
                if (moved != null) {
                    classRefs.computeIfAbsent(moved.from(), k -> new ClassRef(moved, path, new int[1])).count()[0]++;
                }
            }
        }

        void reportClassRefs() {
            classRefs.values().forEach(r -> warn("jasperReport", r.moved().from() + " is named " + r.count()[0] + " time(s), first in "
                    + locator(r.firstPath()) + "; " + (r.moved().to() == null ? "JR 7 no longer has it"
                    : "JR 7 moved it to " + r.moved().to() + " (" + RelocatedClasses.module(r.moved().to()) + ")")));
        }

        // ------------------------------------------------------------ source access

        /** The attributes of an element, consumed as they are read; whatever is left over is reported. */
        final class Attrs {
            final String path;
            final Map<String, String> left = new LinkedHashMap<>();

            Attrs(Element e, String path) {
                this.path = path;
                NamedNodeMap map = e.getAttributes();
                for (int i = 0; i < map.getLength(); i++) {
                    String name = map.item(i).getNodeName();
                    if (name.equals("xmlns") || name.startsWith("xmlns:") || name.startsWith("xsi:")) {
                        continue;
                    }
                    left.put(name, map.item(i).getNodeValue());
                }
            }

            String take(String name) {
                return left.remove(name);
            }

            /** Copies attributes that keep their name. */
            void copy(Out to, String... names) {
                for (String n : names) {
                    to.set(n, take(n));
                }
            }

            /** Copies an {@code isXxx} boolean as {@code xxx}. */
            void rename(Out to, String... names) {
                for (String n : names) {
                    String v = take(n);
                    if (v != null) {
                        to.set(Character.toLowerCase(n.charAt(2)) + n.substring(3), bool(v));
                    }
                }
            }

            void map(Out to, String from, String as) {
                to.set(as, take(from));
            }

            void rest() {
                left.keySet().forEach(n -> warn(path, "attribute '" + n + "' has no JR 7 equivalent and was dropped"));
                left.clear();
            }
        }

        /** The element children of an element, taken by name; whatever is left over is reported. */
        final class Kids {
            final String path;
            final List<Element> list = new ArrayList<>();
            final Map<Element, List<String>> comments = new HashMap<>();
            final List<String> trailing = new ArrayList<>();
            final boolean[] used;

            Kids(Element parent, String path) {
                this(parent, path, false);
            }

            /** @param textAllowed whether text directly in the element is its value (so not reported as dropped) */
            Kids(Element parent, String path, boolean textAllowed) {
                this.path = path;
                List<String> pending = new ArrayList<>();
                for (Node c = parent.getFirstChild(); c != null; c = c.getNextSibling()) {
                    if (c instanceof Element ce) {
                        list.add(ce);
                        if (!pending.isEmpty()) {
                            comments.put(ce, pending);
                            pending = new ArrayList<>();
                        }
                    } else if (c instanceof Comment cm) {
                        pending.add(cm.getData());
                    } else if (!textAllowed && (c.getNodeType() == Node.TEXT_NODE || c.getNodeType() == Node.CDATA_SECTION_NODE)
                            && !c.getNodeValue().isBlank()) {
                        warn(path, "text content outside an expression was dropped");
                    }
                }
                trailing.addAll(pending);
                used = new boolean[list.size()];
            }

            Element one(String name) {
                for (int i = 0; i < list.size(); i++) {
                    if (!used[i] && name.equals(localName(list.get(i)))) {
                        used[i] = true;
                        return list.get(i);
                    }
                }
                return null;
            }

            List<Element> all(String name) {
                List<Element> r = new ArrayList<>();
                for (int i = 0; i < list.size(); i++) {
                    if (!used[i] && name.equals(localName(list.get(i)))) {
                        used[i] = true;
                        r.add(list.get(i));
                    }
                }
                return r;
            }

            /** In document order, those whose name is in {@code names}. */
            List<Element> allOf(Set<String> names) {
                List<Element> r = new ArrayList<>();
                for (int i = 0; i < list.size(); i++) {
                    if (!used[i] && names.contains(localName(list.get(i)))) {
                        used[i] = true;
                        r.add(list.get(i));
                    }
                }
                return r;
            }

            /** Names the comments that came before {@code e} onto its output. */
            Out lead(Element e, Out out) {
                List<String> c = comments.remove(e);
                if (c != null && out != null) {
                    out.leading.addAll(0, c);
                }
                return out;
            }

            /** What a wrapper that disappears (reportElement, textElement, ...) had in front of it. */
            void carry(Element wrapper, Out owner) {
                List<String> c = comments.remove(wrapper);
                if (c != null) {
                    owner.leading.addAll(c);
                }
            }

            void rest() {
                for (int i = 0; i < list.size(); i++) {
                    if (!used[i]) {
                        warn(path, "element <" + localName(list.get(i)) + "> has no JR 7 equivalent and was dropped");
                    }
                }
                Arrays.fill(used, true);
            }

            void flushComments(Out owner) {
                comments.values().forEach(owner.leading::addAll);
                comments.clear();
                owner.trailing.addAll(trailing);
                trailing.clear();
            }
        }

        String sub(String path, Element e) {
            String name = localName(e);
            String id = e.getAttribute("name");
            return path + "/" + name + (id.isEmpty() ? "" : "[" + id + "]");
        }

        static String bool(String v) {
            return "true".equalsIgnoreCase(v.trim()) ? "true" : "false";
        }

        // ------------------------------------------------------------ text and expressions

        /** The text of an element as segments; {@code trim} removes the whitespace around the content (CDATA is never touched). */
        List<Seg> segs(Element e, boolean trim, String path) {
            List<Seg> segs = new ArrayList<>();
            for (Node c = e.getFirstChild(); c != null; c = c.getNextSibling()) {
                switch (c.getNodeType()) {
                    case Node.TEXT_NODE -> segs.add(new Seg(c.getNodeValue(), false));
                    case Node.CDATA_SECTION_NODE -> segs.add(new Seg(c.getNodeValue(), true));
                    case Node.ELEMENT_NODE -> warn(path, "element <" + localName(c) + "> inside the text was dropped");
                    default -> {
                    }
                }
            }
            if (trim) {
                while (!segs.isEmpty() && !segs.get(0).cdata() && segs.get(0).text().isBlank()) {
                    segs.remove(0);
                }
                while (!segs.isEmpty() && !segs.get(segs.size() - 1).cdata() && segs.get(segs.size() - 1).text().isBlank()) {
                    segs.remove(segs.size() - 1);
                }
                if (!segs.isEmpty() && !segs.get(0).cdata()) {
                    segs.set(0, new Seg(segs.get(0).text().stripLeading(), false));
                }
                int last = segs.size() - 1;
                if (last >= 0 && !segs.get(last).cdata()) {
                    segs.set(last, new Seg(segs.get(last).text().stripTrailing(), false));
                }
            }
            return segs;
        }

        /**
         * An expression element renamed to {@code as}. The {@code class} attribute JR 6 allowed on expressions is not
         * part of JR 7 (the value class comes from where the expression is used), so it is dropped on purpose.
         */
        Out expr(String as, Element e, String path) {
            Attrs a = new Attrs(e, path);
            Out out = new Out(as);
            a.copy(out, "type");
            a.take("class");
            a.rest();
            out.text = segs(e, true, path);
            out.text.forEach(seg -> scanClasses(seg.text(), path));
            return out;
        }

        Out exprOf(Kids k, String from, String as) {
            Element e = k.one(from);
            return e == null ? null : k.lead(e, expr(as, e, k.path + "/" + from));
        }

        void exprs(Kids k, Out to, String... names) {
            for (String n : names) {
                to.add(exprOf(k, n, n));
            }
        }

        // ------------------------------------------------------------ properties

        void properties(Kids k, Out to) {
            for (Element p : k.all("property")) {
                String path = k.path + "/property";
                Attrs a = new Attrs(p, path);
                Out out = new Out("property");
                String value = a.take("value");
                String body = segsText(p);
                a.copy(out, "name");
                String removed = RemovedProperties.reason(p.getAttribute("name"));
                if (removed != null) {
                    warn(path + "[" + p.getAttribute("name") + "]", "property '" + p.getAttribute("name") + "' is kept but has no effect: " + removed);
                }
                if (!body.isEmpty()) {
                    value = body;
                }
                out.set("value", value);
                a.rest();
                new Kids(p, path, true).rest();
                to.add(k.lead(p, out));
            }
            for (Element p : k.all("propertyExpression")) {
                to.add(k.lead(p, propertyExpression(p, k.path + "/propertyExpression")));
            }
        }

        Out propertyExpression(Element p, String path) {
            Attrs a = new Attrs(p, path);
            Out out = new Out("propertyExpression");
            a.copy(out, "name");
            a.rest();
            out.text = segs(p, true, path);
            return out;
        }

        String segsText(Element e) {
            StringBuilder sb = new StringBuilder();
            for (Node c = e.getFirstChild(); c != null; c = c.getNextSibling()) {
                if (c.getNodeType() == Node.TEXT_NODE || c.getNodeType() == Node.CDATA_SECTION_NODE) {
                    sb.append(c.getNodeValue());
                }
            }
            return sb.toString().trim();
        }

        // ------------------------------------------------------------ boxes, pens, paragraphs

        static final List<String> SIDES = List.of("pen", "topPen", "leftPen", "bottomPen", "rightPen");

        /** A line box under construction: the JR 6 attribute forms and the child pens end up in the same place. */
        final class BoxSpec {
            final Map<String, String> padding = new LinkedHashMap<>();
            final Map<String, Map<String, String>> pens = new LinkedHashMap<>();

            Map<String, String> pen(String side) {
                return pens.computeIfAbsent(side, s -> new LinkedHashMap<>());
            }

            /** {@code border}, {@code topBorder}, ... and their colors and paddings as the old DTD and JR 6 styles spell them. */
            void legacy(Attrs a, boolean stylePadding) {
                for (String side : SIDES) {
                    String prefix = side.equals("pen") ? "" : side.substring(0, side.length() - 3);
                    String border = a.take(prefix.isEmpty() ? "border" : prefix + "Border");
                    String color = a.take(prefix.isEmpty() ? "borderColor" : prefix + "BorderColor");
                    if (border != null) {
                        legacyPen(pen(side), border);
                    }
                    if (color != null) {
                        pen(side).put("lineColor", color);
                    }
                    String pad = a.take(prefix.isEmpty() ? "padding" : prefix + "Padding");
                    if (pad != null && stylePadding) {
                        padding.put(prefix.isEmpty() ? "padding" : prefix + "Padding", pad);
                    }
                }
            }

            /** A {@code <box>} element. */
            void element(Element box, String path) {
                Attrs a = new Attrs(box, path);
                for (String n : List.of("padding", "topPadding", "leftPadding", "bottomPadding", "rightPadding")) {
                    String v = a.take(n);
                    if (v != null) {
                        padding.put(n, v);
                    }
                }
                legacy(a, false);
                a.rest();
                Kids k = new Kids(box, path);
                for (String side : SIDES) {
                    for (Element p : k.all(side)) {
                        Attrs pa = new Attrs(p, path + "/" + side);
                        Map<String, String> m = pen(side);
                        for (String n : List.of("lineWidth", "lineStyle", "lineColor")) {
                            String v = pa.take(n);
                            if (v != null) {
                                m.put(n, v);
                            }
                        }
                        pa.rest();
                        new Kids(p, path + "/" + side).rest();
                    }
                }
                k.rest();
            }

            Out toOut() {
                Out out = new Out("box");
                padding.forEach(out::set);
                for (String side : SIDES) {
                    Map<String, String> m = pens.get(side);
                    if (m != null && !m.isEmpty()) {
                        Out pen = new Out(side);
                        m.forEach(pen::set);
                        out.add(pen);
                    }
                }
                return out.isEmpty() ? null : out;
            }
        }

        /** What JR 6's PenEnum ({@code Thin}, {@code 1Point}, ...) means as line width and style. */
        void legacyPen(Map<String, String> pen, String value) {
            switch (value) {
                case "Thin" -> {
                    pen.put("lineWidth", "0.5");
                    pen.put("lineStyle", "Solid");
                }
                case "1Point" -> {
                    pen.put("lineWidth", "1.0");
                    pen.put("lineStyle", "Solid");
                }
                case "2Point" -> {
                    pen.put("lineWidth", "2.0");
                    pen.put("lineStyle", "Solid");
                }
                case "4Point" -> {
                    pen.put("lineWidth", "4.0");
                    pen.put("lineStyle", "Solid");
                }
                case "Dotted" -> {
                    pen.put("lineWidth", "1.0");
                    pen.put("lineStyle", "Dashed");
                }
                case "None" -> {
                    pen.put("lineWidth", "0.0");
                    pen.put("lineStyle", "Solid");
                }
                default -> {
                }
            }
        }

        Out box(Kids k) {
            Element e = k.one("box");
            if (e == null) {
                return null;
            }
            BoxSpec spec = new BoxSpec();
            spec.element(e, k.path + "/box");
            return k.lead(e, spec.toOut());
        }

        Out pen(Element p, String path) {
            Attrs a = new Attrs(p, path);
            Out out = new Out("pen");
            a.copy(out, "lineWidth", "lineStyle", "lineColor");
            a.rest();
            new Kids(p, path).rest();
            return out;
        }

        Out paragraph(Element p, String legacyLineSpacing, String path) {
            Out out = new Out("paragraph");
            if (p != null) {
                Attrs a = new Attrs(p, path);
                a.copy(out, "lineSpacing", "lineSpacingSize", "firstLineIndent", "leftIndent", "rightIndent", "spacingBefore",
                        "spacingAfter", "tabStopWidth");
                a.rest();
                Kids k = new Kids(p, path);
                for (Element t : k.all("tabStop")) {
                    Attrs ta = new Attrs(t, path + "/tabStop");
                    Out tab = new Out("tabStop");
                    ta.copy(tab, "position", "alignment");
                    ta.rest();
                    new Kids(t, path + "/tabStop").rest();
                    out.add(tab);
                }
                k.rest();
            }
            if (legacyLineSpacing != null && !out.attrs.containsKey("lineSpacing")) {
                out.attrs.put("lineSpacing", legacyLineSpacing);
                Map<String, String> sorted = new LinkedHashMap<>();
                sorted.put("lineSpacing", legacyLineSpacing);
                out.attrs.forEach(sorted::putIfAbsent);
                out.attrs.clear();
                out.attrs.putAll(sorted);
            }
            return out.isEmpty() ? null : out;
        }

        // ------------------------------------------------------------ styles

        static final Set<String> TEXT_H = Set.of("Left", "Center", "Right", "Justified");
        static final Set<String> IMAGE_H = Set.of("Left", "Center", "Right");
        static final Set<String> TEXT_V = Set.of("Top", "Middle", "Bottom", "Justified");
        static final Set<String> IMAGE_V = Set.of("Top", "Middle", "Bottom");

        /** {@code <style>}, {@code <reportFont>} and the {@code <style>} inside a conditional style. */
        Out style(Element e, String path, boolean conditional) {
            Attrs a = new Attrs(e, path);
            Kids k = new Kids(e, path);
            Out out = new Out(conditional ? "conditionalStyle" : "style");
            if (!conditional) {
                a.copy(out, "name");
                a.rename(out, "isDefault");
            }
            a.copy(out, "style", "mode", "forecolor", "backcolor", "fill", "radius", "scaleImage", "rotation", "pattern", "fontName",
                    "pdfFontName", "pdfEncoding");
            a.rename(out, "isBlankWhenNull", "isBold", "isItalic", "isUnderline", "isStrikeThrough", "isPdfEmbedded");
            String size = a.take("size");
            if (size != null) {
                // JR 6 (checked on 6.17.0, 6.20.6 and 6.21.5) never read the size of a <reportFont>, so text using it printed at the
                // default 10pt. The declared size is kept: it is what the report's author wrote, and what Jaspersoft Studio 7 shows
                out.set("fontSize", size);
                warn(path, "JR 6 (6.17 to 6.21) ignored the size=\"" + size + "\" of this report font and printed the text using it at the default "
                        + "size; the converted style uses fontSize=\"" + size + "\", so that text now prints at " + size + " (remove fontSize from "
                        + "the style to keep the old look)");
            }
            a.copy(out, "fontSize");
            String hAlign = a.take("hAlign");
            if (hAlign != null) {
                if (TEXT_H.contains(hAlign)) {
                    out.set("hTextAlign", hAlign);
                }
                if (IMAGE_H.contains(hAlign)) {
                    out.set("hImageAlign", hAlign);
                }
            }
            String vAlign = a.take("vAlign");
            if (vAlign != null) {
                if (TEXT_V.contains(vAlign)) {
                    out.set("vTextAlign", vAlign);
                }
                if (IMAGE_V.contains(vAlign)) {
                    out.set("vImageAlign", vAlign);
                }
            }
            a.copy(out, "hTextAlign", "vTextAlign", "hImageAlign", "vImageAlign");
            String styled = a.take("isStyledText");
            if (styled != null) {
                out.set("markup", "true".equalsIgnoreCase(styled) ? "styled" : "none");
            }
            a.copy(out, "markup");
            String legacyLineSpacing = a.take("lineSpacing");
            BoxSpec box = new BoxSpec();
            String legacyPen = a.take("pen");
            Map<String, String> stylePen = new LinkedHashMap<>();
            if (legacyPen != null) {
                legacyPen(stylePen, legacyPen);
            }
            box.legacy(a, true);
            a.rest();

            Element conditionExpression = k.one("conditionExpression");
            if (conditionExpression != null) {
                out.add(k.lead(conditionExpression, expr("conditionExpression", conditionExpression, path + "/conditionExpression")));
            }
            for (Element p : k.all("pen")) {
                Attrs pa = new Attrs(p, path + "/pen");
                for (String n : List.of("lineWidth", "lineStyle", "lineColor")) {
                    String v = pa.take(n);
                    if (v != null) {
                        stylePen.put(n, v);
                    }
                }
                pa.rest();
                new Kids(p, path + "/pen").rest();
            }
            if (!stylePen.isEmpty()) {
                Out pen = new Out("pen");
                stylePen.forEach(pen::set);
                out.add(pen);
            }
            Element boxEl = k.one("box");
            if (boxEl != null) {
                box.element(boxEl, path + "/box");
            }
            out.add(box.toOut());
            Element parag = k.one("paragraph");
            out.add(paragraph(parag, legacyLineSpacing, path + "/paragraph"));
            if (!conditional) {
                for (Element c : k.all("conditionalStyle")) {
                    String cpath = path + "/conditionalStyle";
                    new Attrs(c, cpath).rest();
                    Kids ck = new Kids(c, cpath);
                    Out cs = new Out("conditionalStyle");
                    Element inner = ck.one("style");
                    Element cond = ck.one("conditionExpression");
                    if (inner != null) {
                        cs = style(inner, cpath + "/style", true);
                    }
                    if (cond != null) {
                        cs.kids.add(0, expr("conditionExpression", cond, cpath + "/conditionExpression"));
                    }
                    ck.rest();
                    out.add(k.lead(c, cs.sort(STYLE)));
                }
            }
            k.rest();
            return out.sort(STYLE);
        }

        // ------------------------------------------------------------ report

        Out report(Element root) {
            String path = "jasperReport";
            Attrs a = new Attrs(root, path);
            Kids k = new Kids(root, path);
            Out out = new Out("jasperReport");
            a.copy(out, "name", "language", "columnCount", "printOrder", "columnDirection", "pageWidth", "pageHeight", "orientation",
                    "whenNoDataType", "sectionType", "columnWidth", "columnSpacing", "leftMargin", "rightMargin", "topMargin",
                    "bottomMargin", "scriptletClass", "formatFactoryClass", "resourceBundle", "whenResourceMissingType", "uuid");
            a.rename(out, "isTitleNewPage", "isSummaryNewPage", "isSummaryWithPageHeaderAndFooter", "isFloatColumnFooter", "isIgnorePagination");
            scanClasses(out.attrs.get("scriptletClass"), path);
            a.rest();
            String language = out.attrs.get("language");
            if (language != null && language.equalsIgnoreCase("javascript")) {
                warn(path, "language 'javascript' needs the jasperreports-javascript module in JR 7, which this API does not include");
            }

            for (Element i : k.all("import")) {
                Attrs ia = new Attrs(i, path + "/import");
                Out imp = new Out("import");
                String value = ia.take("value");
                ia.rest();
                new Kids(i, path + "/import", true).rest();
                imp.text = new ArrayList<>(List.of(new Seg(value == null ? segsText(i) : value, false)));
                scanClasses(imp.text.get(0).text(), path + "/import");
                out.add(k.lead(i, imp));
            }
            for (Element t : k.all("template")) {
                out.add(k.lead(t, expr("template", t, path + "/template")));
            }
            // reportFont is a style in JR 6 as well (the digester builds a style from it)
            for (Element e : k.allOf(Set.of("reportFont", "style"))) {
                out.add(k.lead(e, style(e, sub(path, e), false)));
            }
            for (Element d : k.all("subDataset")) {
                out.add(k.lead(d, subDataset(d, sub(path, d))));
            }
            datasetBody(k, out, path);
            section(k, "background", out, path);
            section(k, "title", out, path);
            section(k, "pageHeader", out, path);
            section(k, "columnHeader", out, path);
            multiSection(k, "detail", out, path);
            section(k, "columnFooter", out, path);
            section(k, "pageFooter", out, path);
            section(k, "lastPageFooter", out, path);
            section(k, "summary", out, path);
            section(k, "noData", out, path);
            k.rest();
            k.flushComments(out);
            reportClassRefs();
            return out.sort(REPORT);
        }

        Out subDataset(Element d, String path) {
            Attrs a = new Attrs(d, path);
            Kids k = new Kids(d, path);
            Out out = new Out("dataset");
            a.copy(out, "name", "scriptletClass", "resourceBundle", "whenResourceMissingType", "uuid");
            a.rest();
            datasetBody(k, out, path);
            k.rest();
            k.flushComments(out);
            return out.sort(DATASET);
        }

        /** What the main dataset and a sub dataset have in common. */
        void datasetBody(Kids k, Out out, String path) {
            properties(k, out);
            for (Element s : k.all("scriptlet")) {
                String p = sub(path, s);
                Attrs a = new Attrs(s, p);
                Kids sk = new Kids(s, p);
                Out sc = new Out("scriptlet");
                a.copy(sc, "name", "class");
                scanClasses(sc.attrs.get("class"), p);
                a.rest();
                Element desc = sk.one("scriptletDescription");
                if (desc != null) {
                    sc.add(expr("description", desc, p + "/scriptletDescription"));
                }
                properties(sk, sc);
                sk.rest();
                out.add(k.lead(s, sc.sort(order("name", "class", "description", "property", "propertyExpression"))));
            }
            for (Element e : k.all("parameter")) {
                String p = sub(path, e);
                Attrs a = new Attrs(e, p);
                Kids pk = new Kids(e, p);
                Out o = new Out("parameter");
                a.copy(o, "name", "class", "evaluationTime", "nestedType");
                a.rename(o, "isForPrompting");
                scanClasses(o.attrs.get("class"), p);
                a.rest();
                Element desc = pk.one("parameterDescription");
                if (desc != null) {
                    o.add(expr("description", desc, p + "/parameterDescription"));
                }
                o.add(exprOf(pk, "defaultValueExpression", "defaultValueExpression"));
                properties(pk, o);
                pk.rest();
                out.add(k.lead(e, o.sort(order("name", "forPrompting", "class", "evaluationTime", "nestedType", "description",
                        "defaultValueExpression", "property", "propertyExpression"))));
            }
            for (Element q : k.all("queryString")) {
                String p = path + "/queryString";
                Attrs a = new Attrs(q, p);
                Out o = new Out("query");
                String language = a.take("language");
                o.set("language", language == null || language.isBlank() ? "sql" : language);
                a.rest();
                o.text = segs(q, true, p);
                if (language != null && !QUERY_LANGUAGES.contains(language.toLowerCase(Locale.ROOT))) {
                    warn(p, "query language '" + language + "' needs a JR 7 query executer extension, which JR 7 does not include by default");
                }
                out.add(k.lead(q, o));
            }
            for (Element f : k.all("field")) {
                String p = sub(path, f);
                Attrs a = new Attrs(f, p);
                Kids fk = new Kids(f, p);
                Out o = new Out("field");
                a.copy(o, "name", "class");
                scanClasses(o.attrs.get("class"), p);
                a.rest();
                Element desc = fk.one("fieldDescription");
                if (desc != null) {
                    o.add(expr("description", desc, p + "/fieldDescription"));
                }
                properties(fk, o);
                fk.rest();
                out.add(k.lead(f, o.sort(order("name", "class", "description", "property", "propertyExpression"))));
            }
            for (Element s : k.all("sortField")) {
                Attrs a = new Attrs(s, sub(path, s));
                Out o = new Out("sortField");
                a.copy(o, "name", "order", "type");
                a.rest();
                new Kids(s, sub(path, s)).rest();
                out.add(k.lead(s, o));
            }
            for (Element v : k.all("variable")) {
                String p = sub(path, v);
                Attrs a = new Attrs(v, p);
                Kids vk = new Kids(v, p);
                Out o = new Out("variable");
                a.copy(o, "name", "class", "resetType", "resetGroup", "incrementType", "incrementGroup", "calculation", "incrementerFactoryClass");
                scanClasses(o.attrs.get("class"), p);
                a.rest();
                Element desc = vk.one("variableDescription");
                if (desc != null) {
                    o.add(expr("description", desc, p + "/variableDescription"));
                }
                o.add(exprOf(vk, "variableExpression", "expression"));
                o.add(exprOf(vk, "initialValueExpression", "initialValueExpression"));
                vk.rest();
                out.add(k.lead(v, o.sort(order("name", "resetType", "incrementType", "calculation", "resetGroup", "incrementGroup",
                        "incrementerFactoryClass", "class", "description", "expression", "initialValueExpression"))));
            }
            Element filter = k.one("filterExpression");
            if (filter != null) {
                out.add(k.lead(filter, expr("filterExpression", filter, path + "/filterExpression")));
            }
            for (Element g : k.all("group")) {
                out.add(k.lead(g, group(g, sub(path, g))));
            }
        }

        static final Set<String> QUERY_LANGUAGES = Set.of("sql", "json", "jsonql", "csv", "xpath");

        Out group(Element g, String path) {
            Attrs a = new Attrs(g, path);
            Kids k = new Kids(g, path);
            Out out = new Out("group");
            a.copy(out, "name", "minHeightToStartNewPage", "minDetailsToStartFromTop", "footerPosition", "keepTogether", "preventOrphanFooter");
            a.rename(out, "isStartNewColumn", "isStartNewPage", "isResetPageNumber", "isReprintHeaderOnEachPage", "isReprintHeaderOnEachColumn");
            a.rest();
            out.add(exprOf(k, "groupExpression", "expression"));
            multiSection(k, "groupHeader", out, path);
            multiSection(k, "groupFooter", out, path);
            k.rest();
            k.flushComments(out);
            return out.sort(order("name", "startNewColumn", "startNewPage", "resetPageNumber", "reprintHeaderOnEachPage",
                    "reprintHeaderOnEachColumn", "minHeightToStartNewPage", "minDetailsToStartFromTop", "footerPosition", "keepTogether",
                    "preventOrphanFooter", "expression", "groupHeader", "groupFooter"));
        }

        // ------------------------------------------------------------ sections and bands

        /** A section that holds one band: JR 7 puts the band's attributes on the section element itself. */
        void section(Kids k, String name, Out to, String path) {
            Element sec = k.one(name);
            if (sec == null) {
                return;
            }
            String p = path + "/" + name;
            Kids sk = new Kids(sec, p);
            Attrs a = new Attrs(sec, p);
            a.rest();
            List<Element> bands = sk.all("band");
            Out out = new Out(name);
            if (!bands.isEmpty()) {
                band(bands.get(0), out, p + "/band");
                for (int i = 1; i < bands.size(); i++) {
                    warn(p + "/band[" + i + "]", "a " + name + " section holds one band, this extra band was dropped");
                }
            }
            sk.rest();
            sk.flushComments(out);
            to.add(k.lead(sec, out));
        }

        /** detail, groupHeader and groupFooter may hold several bands. */
        void multiSection(Kids k, String name, Out to, String path) {
            List<Element> secs = k.all(name);
            for (Element sec : secs) {
                String p = path + "/" + name;
                Kids sk = new Kids(sec, p);
                Attrs a = new Attrs(sec, p);
                a.rest();
                Out out = new Out(name);
                List<Element> bands = sk.all("band");
                for (int i = 0; i < bands.size(); i++) {
                    Out b = new Out("band");
                    band(bands.get(i), b, p + "/band" + (bands.size() > 1 ? "[" + i + "]" : ""));
                    out.add(sk.lead(bands.get(i), b));
                }
                sk.rest();
                sk.flushComments(out);
                to.add(k.lead(sec, out));
            }
        }

        static final Set<String> ELEMENTS = Set.of("break", "line", "rectangle", "ellipse", "image", "staticText", "textField", "subreport",
                "elementGroup", "crosstab", "frame", "componentElement", "genericElement", "pieChart", "pie3DChart", "barChart", "bar3DChart",
                "xyBarChart", "stackedBarChart", "stackedBar3DChart", "lineChart", "xyLineChart", "areaChart", "xyAreaChart", "scatterChart",
                "bubbleChart", "timeSeriesChart", "highLowChart", "candlestickChart", "meterChart", "thermometerChart", "multiAxisChart",
                "stackedAreaChart", "ganttChart", "part");

        void band(Element b, Out out, String path) {
            Attrs a = new Attrs(b, path);
            Kids k = new Kids(b, path);
            a.copy(out, "height", "splitType");
            String allowed = a.take("isSplitAllowed");
            if (allowed != null && !out.attrs.containsKey("splitType")) {
                out.set("splitType", "true".equalsIgnoreCase(allowed) ? "Stretch" : "Prevent");
            }
            a.rest();
            properties(k, out);
            out.add(exprOf(k, "printWhenExpression", "printWhenExpression"));
            for (Element r : k.all("returnValue")) {
                out.add(returnValue(r, path + "/returnValue", "expression"));
            }
            elements(k, out, path);
            k.rest();
            k.flushComments(out);
            out.sort(BAND);
        }

        /** Converts the report elements among the children of {@code k} (in document order) into {@code <element>}s. */
        void elements(Kids k, Out to, String path) {
            int index = 0;
            for (Element e : k.allOf(ELEMENTS)) {
                Out o = element(e, path + "/" + localName(e) + "[" + index + "]");
                index++;
                to.add(k.lead(e, o));
            }
        }

        Out element(Element e, String path) {
            return switch (localName(e)) {
                case "textField" -> textField(e, path);
                case "staticText" -> staticText(e, path);
                case "image" -> image(e, path);
                case "line" -> line(e, path);
                case "rectangle" -> rectangle(e, path);
                case "ellipse" -> ellipse(e, path);
                case "break" -> breakElement(e, path);
                case "frame" -> frame(e, path);
                case "subreport" -> subreport(e, path);
                case "elementGroup" -> elementGroup(e, path);
                case "componentElement" -> componentElement(e, path);
                case "genericElement" -> genericElement(e, path);
                case "crosstab" -> crosstab(e, path);
                case "part" -> unsupported(path, "report parts (<part>) are not converted");
                default -> unsupported(path, "<" + localName(e) + "> is a chart; charts are not converted (JR 7 needs the jasperreports-charts "
                        + "module for them, and this API does not ship it)");
            };
        }

        Out unsupported(String path, String why) {
            warn(path, why + ", the element was left out");
            return Out.comment(" not converted: " + locator(path) + " ");
        }

        // ------------------------------------------------------------ elements

        Out newElement(String kind) {
            return new Out("element").set("kind", kind);
        }

        /** {@code <reportElement>}: position, size, colors, key, style and the element's properties and conditions. */
        void reportElement(Out to, Kids k, String path) {
            Element re = k.one("reportElement");
            if (re == null) {
                warn(path, "the element has no <reportElement>; it cannot be placed");
                return;
            }
            k.carry(re, to);
            String p = path + "/reportElement";
            Attrs a = new Attrs(re, p);
            a.copy(to, "uuid", "key", "x", "y", "width", "height", "forecolor", "backcolor", "mode", "positionType", "printWhenGroupChanges", "style");
            String stretch = a.take("stretchType");
            if (stretch != null) {
                to.set("stretchType", stretchType(stretch));
            }
            a.rename(to, "isPrintRepeatedValues", "isPrintInFirstWholeBand", "isPrintWhenDetailOverflows", "isRemoveLineWhenBlank");
            a.rest();
            Kids rk = new Kids(re, p);
            properties(rk, to);
            to.add(exprOf(rk, "styleExpression", "styleExpression"));
            to.add(exprOf(rk, "printWhenExpression", "printWhenExpression"));
            rk.rest();
        }

        /** JR 6's two original stretch types are the element-group and container variants in JR 7. */
        static String stretchType(String v) {
            return switch (v) {
                case "RelativeToTallestObject" -> "ElementGroupHeight";
                case "RelativeToBandHeight" -> "ContainerHeight";
                default -> v;
            };
        }

        /** {@code <graphicElement>}: fill, the pen, and the deprecated pen and stretch attributes. */
        void graphicElement(Out to, Kids k, String path) {
            Element g = k.one("graphicElement");
            if (g == null) {
                return;
            }
            k.carry(g, to);
            String p = path + "/graphicElement";
            Attrs a = new Attrs(g, p);
            a.copy(to, "fill");
            String stretch = a.take("stretchType");
            if (stretch != null) {
                to.set("stretchType", stretchType(stretch));
            }
            Map<String, String> pen = new LinkedHashMap<>();
            String legacy = a.take("pen");
            if (legacy != null) {
                legacyPen(pen, legacy);
            }
            a.rest();
            Kids gk = new Kids(g, p);
            for (Element pe : gk.all("pen")) {
                Out o = pen(pe, p + "/pen");
                pen.putAll(o.attrs);
            }
            gk.rest();
            if (!pen.isEmpty()) {
                Out o = new Out("pen");
                pen.forEach(o::set);
                to.add(o);
            }
        }

        void hyperlinks(Out to, Attrs a, Kids k, String path) {
            a.map(to, "hyperlinkType", "linkType");
            a.map(to, "hyperlinkTarget", "linkTarget");
            a.copy(to, "bookmarkLevel");
            exprs(k, to, "anchorNameExpression", "bookmarkLevelExpression", "hyperlinkReferenceExpression", "hyperlinkWhenExpression",
                    "hyperlinkAnchorExpression", "hyperlinkPageExpression", "hyperlinkTooltipExpression");
            for (Element h : k.all("hyperlinkParameter")) {
                String p = path + "/hyperlinkParameter";
                Attrs ha = new Attrs(h, p);
                Kids hk = new Kids(h, p);
                Out o = new Out("hyperlinkParameter");
                ha.copy(o, "name");
                ha.rest();
                o.add(exprOf(hk, "hyperlinkParameterExpression", "expression"));
                hk.rest();
                to.add(k.lead(h, o));
            }
        }

        /** The evaluation group only counts when the evaluation time is Group (JR 6 ignores it otherwise). */
        void evaluationTime(Out to, Attrs a, String path) {
            String time = a.take("evaluationTime");
            String group = a.take("evaluationGroup");
            to.set("evaluationTime", time);
            if (group != null) {
                if ("Group".equals(time)) {
                    to.set("evaluationGroup", group);
                } else {
                    warn(path, "evaluationGroup '" + group + "' is ignored when the evaluation time is not Group, so it was dropped");
                }
            }
        }

        /** {@code <textElement>}: alignment, rotation, markup, the font and the paragraph. */
        void textElement(Out to, Kids k, String path) {
            Element te = k.one("textElement");
            Out paragraph = null;
            if (te != null) {
                k.carry(te, to);
                String p = path + "/textElement";
                Attrs a = new Attrs(te, p);
                Kids tk = new Kids(te, p);
                a.map(to, "textAlignment", "hTextAlign");
                a.map(to, "verticalAlignment", "vTextAlign");
                a.copy(to, "rotation", "markup");
                String styled = a.take("isStyledText");
                if (styled != null) {
                    to.set("markup", "true".equalsIgnoreCase(styled) ? "styled" : "none");
                }
                String lineSpacing = a.take("lineSpacing");
                a.rest();
                Element font = tk.one("font");
                if (font != null) {
                    font(font, to, p + "/font");
                }
                paragraph = paragraph(tk.one("paragraph"), lineSpacing, p + "/paragraph");
                tk.rest();
            }
            to.add(paragraph);
        }

        void font(Element f, Out to, String path) {
            Attrs a = new Attrs(f, path);
            a.copy(to, "fontName", "pdfFontName", "pdfEncoding");
            a.map(to, "size", "fontSize");
            a.rename(to, "isBold", "isItalic", "isUnderline", "isStrikeThrough", "isPdfEmbedded");
            String reportFont = a.take("reportFont");
            a.rest();
            new Kids(f, path).rest();
            // a report font is a style; it applies only when the element has no style of its own (as in JR 6)
            if (reportFont != null && !to.attrs.containsKey("style")) {
                to.set("style", reportFont);
            }
        }

        Out textField(Element e, String path) {
            Attrs a = new Attrs(e, path);
            Kids k = new Kids(e, path);
            Out out = newElement("textField");
            reportElement(out, k, path);
            out.add(box(k));
            textElement(out, k, path);
            String stretch = a.take("isStretchWithOverflow");
            if (stretch != null && "true".equalsIgnoreCase(stretch)) {
                out.set("textAdjust", "StretchHeight");
            }
            a.copy(out, "textAdjust", "pattern");
            evaluationTime(out, a, path);
            a.rename(out, "isBlankWhenNull");
            hyperlinks(out, a, k, path);
            a.rest();
            out.add(exprOf(k, "textFieldExpression", "expression"));
            exprs(k, out, "patternExpression");
            k.rest();
            k.flushComments(out);
            return out.sort(TEXT_FIELD);
        }

        Out staticText(Element e, String path) {
            Attrs a = new Attrs(e, path);
            Kids k = new Kids(e, path);
            Out out = newElement("staticText");
            reportElement(out, k, path);
            out.add(box(k));
            textElement(out, k, path);
            a.rest();
            Element text = k.one("text");
            if (text != null) {
                Out t = new Out("text");
                t.text = segs(text, false, path + "/text");
                new Attrs(text, path + "/text").rest();
                out.add(k.lead(text, t));
            }
            k.rest();
            k.flushComments(out);
            return out.sort(STATIC_TEXT);
        }

        Out image(Element e, String path) {
            Attrs a = new Attrs(e, path);
            Kids k = new Kids(e, path);
            Out out = newElement("image");
            reportElement(out, k, path);
            out.add(box(k));
            graphicElement(out, k, path);
            a.copy(out, "scaleImage", "rotation", "onErrorType");
            a.map(out, "hAlign", "hImageAlign");
            a.map(out, "vAlign", "vImageAlign");
            a.rename(out, "isUsingCache", "isLazy");
            evaluationTime(out, a, path);
            hyperlinks(out, a, k, path);
            a.rest();
            out.add(exprOf(k, "imageExpression", "expression"));
            k.rest();
            k.flushComments(out);
            return out.sort(IMAGE);
        }

        Out line(Element e, String path) {
            Attrs a = new Attrs(e, path);
            Kids k = new Kids(e, path);
            Out out = newElement("line");
            reportElement(out, k, path);
            graphicElement(out, k, path);
            a.copy(out, "direction");
            a.rest();
            k.rest();
            k.flushComments(out);
            return out.sort(LINE);
        }

        Out rectangle(Element e, String path) {
            Attrs a = new Attrs(e, path);
            Kids k = new Kids(e, path);
            Out out = newElement("rectangle");
            reportElement(out, k, path);
            graphicElement(out, k, path);
            a.copy(out, "radius");
            a.rest();
            k.rest();
            k.flushComments(out);
            return out.sort(RECTANGLE);
        }

        Out ellipse(Element e, String path) {
            Attrs a = new Attrs(e, path);
            Kids k = new Kids(e, path);
            Out out = newElement("ellipse");
            reportElement(out, k, path);
            graphicElement(out, k, path);
            a.rest();
            k.rest();
            k.flushComments(out);
            return out.sort(ELLIPSE);
        }

        Out breakElement(Element e, String path) {
            Attrs a = new Attrs(e, path);
            Kids k = new Kids(e, path);
            Out out = newElement("break");
            reportElement(out, k, path);
            a.copy(out, "type");
            a.rest();
            k.rest();
            k.flushComments(out);
            return out.sort(BREAK);
        }

        Out frame(Element e, String path) {
            Attrs a = new Attrs(e, path);
            Kids k = new Kids(e, path);
            Out out = newElement("frame");
            reportElement(out, k, path);
            out.add(box(k));
            a.copy(out, "borderSplitType");
            a.rest();
            elements(k, out, path);
            k.rest();
            k.flushComments(out);
            return out.sort(FRAME);
        }

        Out elementGroup(Element e, String path) {
            Attrs a = new Attrs(e, path);
            Kids k = new Kids(e, path);
            Out out = newElement("elementGroup");
            a.rest();
            elements(k, out, path);
            k.rest();
            k.flushComments(out);
            return out;
        }

        Out subreport(Element e, String path) {
            Attrs a = new Attrs(e, path);
            Kids k = new Kids(e, path);
            Out out = newElement("subreport");
            reportElement(out, k, path);
            a.rename(out, "isUsingCache");
            a.copy(out, "runToBottom", "overflowType");
            a.rest();
            exprs(k, out, "parametersMapExpression");
            for (Element p : k.all("subreportParameter")) {
                String pp = path + "/subreportParameter";
                Attrs pa = new Attrs(p, pp);
                Kids pk = new Kids(p, pp);
                Out o = new Out("parameter");
                pa.copy(o, "name");
                pa.rest();
                o.add(exprOf(pk, "subreportParameterExpression", "expression"));
                pk.rest();
                out.add(k.lead(p, o));
            }
            exprs(k, out, "connectionExpression", "dataSourceExpression");
            out.add(exprOf(k, "subreportExpression", "expression"));
            for (Element r : k.all("returnValue")) {
                out.add(returnValue(r, path + "/returnValue", null));
            }
            k.rest();
            k.flushComments(out);
            return out.sort(SUBREPORT);
        }

        /** A {@code <returnValue>}; {@code expressionName} is the child that holds the value expression, if the kind has one. */
        Out returnValue(Element r, String path, String expressionName) {
            Attrs a = new Attrs(r, path);
            Kids k = new Kids(r, path);
            Out o = new Out("returnValue");
            a.copy(o, "subreportVariable", "fromVariable", "toVariable", "calculation", "incrementerFactoryClass");
            a.rest();
            if (expressionName != null) {
                o.add(exprOf(k, "expression", "expression"));
            }
            k.rest();
            return o;
        }

        Out genericElement(Element e, String path) {
            Attrs a = new Attrs(e, path);
            Kids k = new Kids(e, path);
            Out out = newElement("generic");
            reportElement(out, k, path);
            evaluationTime(out, a, path);
            a.rest();
            Element type = k.one("genericElementType");
            if (type != null) {
                Attrs ta = new Attrs(type, path + "/genericElementType");
                Out t = new Out("genericType");
                ta.copy(t, "namespace", "name");
                ta.rest();
                new Kids(type, path + "/genericElementType").rest();
                out.add(t.sort(order("namespace", "name")));
            }
            for (Element p : k.all("genericElementParameter")) {
                String pp = path + "/genericElementParameter";
                Attrs pa = new Attrs(p, pp);
                Kids pk = new Kids(p, pp);
                Out o = new Out("parameter");
                pa.copy(o, "name");
                pa.map(o, "skipWhenNull", "skipWhenEmpty");
                pa.rest();
                o.add(exprOf(pk, "valueExpression", "expression"));
                pk.rest();
                out.add(o.sort(order("name", "skipWhenEmpty", "expression")));
            }
            k.rest();
            k.flushComments(out);
            return out.sort(GENERIC);
        }

        Out datasetRun(Element d, String path) {
            Attrs a = new Attrs(d, path);
            Kids k = new Kids(d, path);
            Out out = new Out("datasetRun");
            a.copy(out, "uuid", "subDataset");
            a.rest();
            properties(k, out);
            for (Element p : k.all("datasetParameter")) {
                String pp = path + "/datasetParameter";
                Attrs pa = new Attrs(p, pp);
                Kids pk = new Kids(p, pp);
                Out o = new Out("parameter");
                pa.copy(o, "name");
                pa.rest();
                o.add(exprOf(pk, "datasetParameterExpression", "expression"));
                pk.rest();
                out.add(k.lead(p, o));
            }
            exprs(k, out, "parametersMapExpression", "connectionExpression", "dataSourceExpression");
            for (Element r : k.all("returnValue")) {
                out.add(returnValue(r, path + "/returnValue", null));
            }
            k.rest();
            return out.sort(order("uuid", "subDataset", "property", "parameter", "parametersMapExpression", "connectionExpression",
                    "dataSourceExpression", "returnValue"));
        }

        // ------------------------------------------------------------ components

        Out componentElement(Element e, String path) {
            Attrs a = new Attrs(e, path);
            Kids k = new Kids(e, path);
            Out out = newElement("component");
            reportElement(out, k, path);
            a.rest();
            Element component = null;
            for (int i = 0; i < k.list.size(); i++) {
                if (!k.used[i]) {
                    component = k.list.get(i);
                    k.used[i] = true;
                    break;
                }
            }
            if (component == null) {
                warn(path, "the component element holds no component");
                return out.sort(COMPONENT);
            }
            String name = localName(component);
            String cpath = path + "/" + name;
            if (!COMPONENTS_NS.equals(component.getNamespaceURI())) {
                return unsupported(cpath, "the component <" + name + "> is not in the JasperReports components namespace");
            }
            Out c = switch (name) {
                case "table" -> table(component, cpath);
                case "list" -> list(component, cpath);
                case "barbecue" -> barbecue(component, cpath);
                case "Codabar", "Code128", "EAN128", "DataMatrix", "Code39", "Interleaved2Of5", "UPCA", "UPCE", "EAN13", "EAN8",
                     "RoyalMailCustomer", "USPSIntelligentMail", "POSTNET", "PDF417", "QRCode" -> barcode4j(component, cpath);
                default -> null;
            };
            if (c == null) {
                return unsupported(cpath, "the component <" + name + "> is not converted");
            }
            out.add(c);
            k.rest();
            k.flushComments(out);
            return out.sort(COMPONENT);
        }

        /** What the barcode4j components of JR 6 (components.xsd) take, over all barcode types; JR 7 spells them the same. */
        static final String[] BARCODE_ATTRIBUTES = {"evaluationTime", "evaluationGroup", "moduleWidth", "textPosition", "quietZone",
                "verticalQuietZone", "wideFactor", "checksumMode", "shape", "minSymbolWidth", "maxSymbolWidth", "minSymbolHeight", "maxSymbolHeight",
                "displayChecksum", "displayStartStop", "extendedCharSetEnabled", "intercharGapWidth", "ascenderHeight", "trackHeight",
                "shortBarHeight", "baselinePosition", "minColumns", "maxColumns", "minRows", "maxRows", "widthToHeightRatio",
                "errorCorrectionLevel", "margin", "qrVersion"};

        Out barcode4j(Element e, String path) {
            Attrs a = new Attrs(e, path);
            Kids k = new Kids(e, path);
            Out out = new Out("component").set("kind", "barcode4j:" + localName(e));
            String orientation = a.take("orientation");
            if (orientation != null) {
                out.set("orientation", switch (orientation) {
                    case "0" -> "up";
                    case "90" -> "left";
                    case "180" -> "down";
                    case "270" -> "right";
                    default -> orientation;
                });
            }
            a.copy(out, BARCODE_ATTRIBUTES);
            a.rest();
            for (String name : List.of("codeExpression", "patternExpression", "templateExpression")) {
                out.add(exprOf(k, name, name));
            }
            k.rest();
            return out.sort(order("kind", "evaluationTime", "evaluationGroup", "orientation", "moduleWidth", "textPosition", "quietZone",
                    "verticalQuietZone", "codeExpression", "patternExpression"));
        }

        Out barbecue(Element e, String path) {
            Attrs a = new Attrs(e, path);
            Kids k = new Kids(e, path);
            Out out = new Out("component").set("kind", "barbecue");
            a.copy(out, "type", "drawText", "checksumRequired", "barWidth", "barHeight", "rotation", "evaluationTime", "evaluationGroup");
            a.rest();
            exprs(k, out, "codeExpression", "applicationIdentifierExpression");
            k.rest();
            warn(path, "the barbecue component needs the jasperreports-barbecue module, which this API does not ship; "
                    + "consider barcode4j instead");
            return out.sort(order("kind", "type", "drawText", "checksumRequired", "barWidth", "barHeight", "rotation", "evaluationTime",
                    "evaluationGroup", "codeExpression", "applicationIdentifierExpression"));
        }

        Out list(Element e, String path) {
            Attrs a = new Attrs(e, path);
            Kids k = new Kids(e, path);
            Out out = new Out("component").set("kind", "list");
            a.copy(out, "printOrder", "ignoreWidth");
            a.rest();
            Element run = k.one("datasetRun");
            if (run != null) {
                out.add(datasetRun(run, path + "/datasetRun"));
            }
            Element contents = k.one("listContents");
            if (contents != null) {
                String p = path + "/listContents";
                Attrs ca = new Attrs(contents, p);
                Kids ck = new Kids(contents, p);
                Out c = new Out("contents");
                ca.copy(c, "height", "width");
                ca.rest();
                elements(ck, c, p);
                ck.rest();
                out.add(c.sort(order("width", "height", "element")));
            }
            k.rest();
            return out.sort(order("kind", "printOrder", "ignoreWidth", "datasetRun", "contents"));
        }

        static final List<String> CELL = order("height", "rowSpan", "style", "property", "box", "element");

        Out table(Element e, String path) {
            Attrs a = new Attrs(e, path);
            Kids k = new Kids(e, path);
            Out out = new Out("component").set("kind", "table");
            a.copy(out, "whenNoDataType", "horizontalPosition", "shrinkWidth");
            a.rest();
            Element run = k.one("datasetRun");
            if (run != null) {
                out.add(datasetRun(run, path + "/datasetRun"));
            }
            for (Element c : k.allOf(Set.of("column", "columnGroup"))) {
                out.add(k.lead(c, tableColumn(c, path + "/" + localName(c))));
            }
            for (String row : List.of("tableHeader", "columnHeader")) {
                tableRow(k, row, out, path);
            }
            for (Element g : k.all("groupHeader")) {
                out.add(tableGroupRow(g, path + "/groupHeader"));
            }
            tableRow(k, "detail", out, path);
            for (Element g : k.all("groupFooter")) {
                out.add(tableGroupRow(g, path + "/groupFooter"));
            }
            for (String row : List.of("columnFooter", "tableFooter")) {
                tableRow(k, row, out, path);
            }
            Element noData = k.one("noData");
            if (noData != null) {
                out.add(tableCell(noData, "noData", path + "/noData"));
            }
            k.rest();
            return out.sort(order("kind", "whenNoDataType", "horizontalPosition", "shrinkWidth", "datasetRun", "column", "tableHeader",
                    "columnHeader", "groupHeader", "groupFooter", "columnFooter", "tableFooter", "detail", "noData"));
        }

        void tableRow(Kids k, String name, Out to, String path) {
            Element r = k.one(name);
            if (r != null) {
                to.add(k.lead(r, tableRowNode(r, name, path + "/" + name)));
            }
        }

        Out tableRowNode(Element r, String name, String path) {
            Attrs a = new Attrs(r, path);
            Kids k = new Kids(r, path);
            Out out = new Out(name);
            a.copy(out, "splitType");
            a.rest();
            out.add(exprOf(k, "printWhenExpression", "printWhenExpression"));
            k.rest();
            return out;
        }

        Out tableGroupRow(Element g, String path) {
            Attrs a = new Attrs(g, path);
            Kids k = new Kids(g, path);
            Out out = new Out(localName(g));
            a.copy(out, "groupName");
            a.rest();
            Element row = k.one("row");
            if (row != null) {
                out.add(tableRowNode(row, "row", path + "/row"));
            }
            k.rest();
            return out;
        }

        Out tableColumn(Element c, String path) {
            boolean group = "columnGroup".equals(localName(c));
            Attrs a = new Attrs(c, path);
            Kids k = new Kids(c, path);
            Out out = new Out("column").set("kind", group ? "group" : "single");
            a.copy(out, "uuid", "width", "weight");
            a.rest();
            properties(k, out);
            out.add(exprOf(k, "printWhenExpression", "printWhenExpression"));
            for (String cell : List.of("tableHeader", "tableFooter", "columnHeader", "columnFooter")) {
                Element ce = k.one(cell);
                if (ce != null) {
                    out.add(k.lead(ce, tableCell(ce, cell, path + "/" + cell)));
                }
            }
            for (String name : List.of("groupHeader", "groupFooter")) {
                for (Element g : k.all(name)) {
                    String gp = path + "/" + name;
                    Attrs ga = new Attrs(g, gp);
                    Kids gk = new Kids(g, gp);
                    Out go = new Out(name);
                    ga.copy(go, "groupName");
                    ga.rest();
                    Element cell = gk.one("cell");
                    if (cell != null) {
                        go.add(tableCell(cell, "cell", gp + "/cell"));
                    }
                    gk.rest();
                    out.add(k.lead(g, go));
                }
            }
            Element detail = k.one("detailCell");
            if (detail != null) {
                out.add(k.lead(detail, tableCell(detail, "detailCell", path + "/detailCell")));
            }
            if (group) {
                for (Element child : k.allOf(Set.of("column", "columnGroup"))) {
                    out.add(k.lead(child, tableColumn(child, path + "/" + localName(child))));
                }
            }
            k.rest();
            return out.sort(order("kind", "uuid", "width", "weight", "property", "propertyExpression", "printWhenExpression", "tableHeader",
                    "columnHeader", "groupHeader", "groupFooter", "columnFooter", "tableFooter", "detailCell", "column"));
        }

        Out tableCell(Element c, String name, String path) {
            Attrs a = new Attrs(c, path);
            Kids k = new Kids(c, path);
            Out out = new Out(name);
            a.copy(out, "height", "rowSpan", "style");
            a.rest();
            properties(k, out);
            out.add(box(k));
            elements(k, out, path);
            k.rest();
            k.flushComments(out);
            return out.sort(CELL);
        }

        // ------------------------------------------------------------ crosstab

        static final List<String> CELL_CONTENTS = order("mode", "backcolor", "style", "property", "box", "element");

        /** The cell contents of a crosstab: in JR 7 their attributes sit on the owning element itself. */
        void cellContentsInto(Out to, Element wrapper, String path) {
            if (wrapper == null) {
                return;
            }
            Kids wk = new Kids(wrapper, path);
            Element cc = wk.one("cellContents");
            if (cc != null) {
                Attrs a = new Attrs(cc, path + "/cellContents");
                Kids k = new Kids(cc, path + "/cellContents");
                a.copy(to, "mode", "backcolor", "style");
                a.rest();
                properties(k, to);
                to.add(box(k));
                elements(k, to, path + "/cellContents");
                k.rest();
            }
            wk.rest();
        }

        Out crosstab(Element e, String path) {
            Attrs a = new Attrs(e, path);
            Kids k = new Kids(e, path);
            Out out = newElement("crosstab");
            reportElement(out, k, path);
            out.add(box(k));
            a.rename(out, "isRepeatColumnHeaders", "isRepeatRowHeaders");
            a.copy(out, "columnBreakOffset", "runDirection", "ignoreWidth", "horizontalPosition");
            a.rest();
            Element data = k.one("crosstabDataset");
            if (data != null) {
                String p = path + "/crosstabDataset";
                Attrs da = new Attrs(data, p);
                Kids dk = new Kids(data, p);
                Out ds = new Out("dataset");
                da.rename(ds, "isDataPreSorted");
                da.rest();
                Element inner = dk.one("dataset");
                if (inner != null) {
                    Attrs ia = new Attrs(inner, p + "/dataset");
                    Kids ik = new Kids(inner, p + "/dataset");
                    ia.copy(ds, "resetType", "resetGroup", "incrementType", "incrementGroup");
                    ia.rest();
                    Element run = ik.one("datasetRun");
                    if (run != null) {
                        ds.add(datasetRun(run, p + "/dataset/datasetRun"));
                    }
                    exprs(ik, ds, "incrementWhenExpression");
                    ik.rest();
                }
                dk.rest();
                out.add(ds.sort(order("resetType", "resetGroup", "incrementType", "incrementGroup", "dataPreSorted", "datasetRun",
                        "incrementWhenExpression")));
            }
            exprs(k, out, "parametersMapExpression");
            for (Element p : k.all("crosstabParameter")) {
                String pp = path + "/crosstabParameter";
                Attrs pa = new Attrs(p, pp);
                Kids pk = new Kids(p, pp);
                Out o = new Out("parameter");
                pa.copy(o, "name", "class");
                pa.rest();
                o.add(exprOf(pk, "parameterValueExpression", "expression"));
                pk.rest();
                out.add(k.lead(p, o.sort(order("name", "class", "expression"))));
            }
            Element header = k.one("crosstabHeaderCell");
            if (header != null) {
                Out h = new Out("headerCell");
                new Attrs(header, path + "/crosstabHeaderCell").rest();
                cellContentsInto(h, header, path + "/crosstabHeaderCell");
                out.add(h.sort(CELL_CONTENTS));
            }
            Element title = k.one("titleCell");
            if (title != null) {
                String p = path + "/titleCell";
                Attrs ta = new Attrs(title, p);
                Out t = new Out("titleCell");
                ta.copy(t, "height", "contentsPosition");
                ta.rest();
                Kids tk = new Kids(title, p);
                Element cc = tk.one("cellContents");
                if (cc != null) {
                    Out contents = new Out("cellContents");
                    Attrs ca = new Attrs(cc, p + "/cellContents");
                    Kids ck = new Kids(cc, p + "/cellContents");
                    ca.copy(contents, "mode", "backcolor", "style");
                    ca.rest();
                    properties(ck, contents);
                    contents.add(box(ck));
                    elements(ck, contents, p + "/cellContents");
                    ck.rest();
                    t.add(contents.sort(CELL_CONTENTS));
                }
                tk.rest();
                out.add(t.sort(order("height", "contentsPosition", "cellContents")));
            }
            for (Element g : k.all("rowGroup")) {
                out.add(k.lead(g, crosstabGroup(g, path + "/rowGroup", true)));
            }
            for (Element g : k.all("columnGroup")) {
                out.add(k.lead(g, crosstabGroup(g, path + "/columnGroup", false)));
            }
            for (Element m : k.all("measure")) {
                String p = sub(path, m);
                Attrs ma = new Attrs(m, p);
                Kids mk = new Kids(m, p);
                Out o = new Out("measure");
                ma.copy(o, "name", "class", "calculation", "incrementerFactoryClass", "percentageCalculatorClass");
                ma.map(o, "percentageOf", "percentageType");
                ma.rest();
                o.add(exprOf(mk, "measureExpression", "expression"));
                mk.rest();
                out.add(k.lead(m, o.sort(order("name", "calculation", "class", "incrementerFactoryClass", "percentageType",
                        "percentageCalculatorClass", "expression"))));
            }
            for (Element c : k.all("crosstabCell")) {
                String p = path + "/crosstabCell";
                Attrs ca = new Attrs(c, p);
                Out o = new Out("cell");
                ca.copy(o, "width", "height", "rowTotalGroup", "columnTotalGroup");
                ca.rest();
                Kids ck = new Kids(c, p);
                Element cc = ck.one("cellContents");
                if (cc != null) {
                    Out contents = new Out("contents");
                    Attrs xa = new Attrs(cc, p + "/cellContents");
                    Kids xk = new Kids(cc, p + "/cellContents");
                    xa.copy(contents, "mode", "backcolor", "style");
                    xa.rest();
                    properties(xk, contents);
                    contents.add(box(xk));
                    elements(xk, contents, p + "/cellContents");
                    xk.rest();
                    o.add(contents.sort(CELL_CONTENTS));
                }
                ck.rest();
                out.add(k.lead(c, o.sort(order("width", "height", "rowTotalGroup", "columnTotalGroup", "contents"))));
            }
            Element noData = k.one("whenNoDataCell");
            if (noData != null) {
                Out n = new Out("whenNoDataCell");
                new Attrs(noData, path + "/whenNoDataCell").rest();
                cellContentsInto(n, noData, path + "/whenNoDataCell");
                out.add(n.sort(CELL_CONTENTS));
            }
            k.rest();
            k.flushComments(out);
            return out.sort(CROSSTAB);
        }

        Out crosstabGroup(Element g, String path, boolean row) {
            Attrs a = new Attrs(g, path);
            Kids k = new Kids(g, path);
            Out out = new Out(row ? "rowGroup" : "columnGroup");
            a.copy(out, "name", "totalPosition", "mergeHeaderCells", row ? "width" : "height", "keepTogether");
            a.map(out, "headerPosition", "position");
            a.rest();
            Element bucket = k.one("bucket");
            if (bucket != null) {
                String p = path + "/bucket";
                Attrs ba = new Attrs(bucket, p);
                Kids bk = new Kids(bucket, p);
                Out b = new Out("bucket");
                ba.copy(b, "order", "class");
                ba.rest();
                b.add(exprOf(bk, "bucketExpression", "expression"));
                exprs(bk, b, "comparatorExpression", "orderByExpression");
                bk.rest();
                out.add(b.sort(order("order", "class", "expression", "comparatorExpression", "orderByExpression")));
            }
            String prefix = row ? "crosstabRow" : "crosstabColumn";
            for (String[] pair : new String[][]{{prefix + "Header", "header"}, {"crosstabTotal" + (row ? "Row" : "Column") + "Header", "totalHeader"},
                    {"crosstabHeader", "crosstabHeader"}}) {
                Element h = k.one(pair[0]);
                if (h != null) {
                    Out o = new Out(pair[1]);
                    new Attrs(h, path + "/" + pair[0]).rest();
                    cellContentsInto(o, h, path + "/" + pair[0]);
                    out.add(k.lead(h, o.sort(CELL_CONTENTS)));
                }
            }
            k.rest();
            return out.sort(order("name", "totalPosition", row ? "width" : "height", "position", "keepTogether", "mergeHeaderCells", "bucket",
                    "header", "totalHeader", "crosstabHeader"));
        }
    }
}
