package com.github.somprasongd.jasperreport.api.jrxml;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Reads the synthetic JR 6 reports under {@code src/test/resources/jrxml6} and the XML helpers the converter tests share. */
final class Jrxml6Fixtures {

    static final Path DIR = Path.of("src/test/resources/jrxml6");

    private Jrxml6Fixtures() {
    }

    static String read(String name) {
        try {
            return Files.readString(DIR.resolve(name), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static LegacyJrxmlConverter.Result convert(String name) {
        return new LegacyJrxmlConverter().convert(read(name));
    }

    /** Parses XML the tests produced themselves (a converter output or a JRXmlWriter result), without any namespace handling. */
    static Document dom(String xml) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            return f.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        } catch (Exception e) {
            throw new IllegalStateException("not well-formed: " + e.getMessage(), e);
        }
    }

    static String xpath(Document doc, String expression) {
        try {
            return XPathFactory.newInstance().newXPath().evaluate(expression, doc);
        } catch (Exception e) {
            throw new IllegalStateException(expression, e);
        }
    }

    static int count(Document doc, String expression) {
        try {
            return ((Double) XPathFactory.newInstance().newXPath().evaluate("count(" + expression + ")", doc, XPathConstants.NUMBER)).intValue();
        } catch (Exception e) {
            throw new IllegalStateException(expression, e);
        }
    }

    static int elementCount(Document doc) {
        return elementCount(doc.getDocumentElement());
    }

    private static int elementCount(Element e) {
        int n = 1;
        NodeList kids = e.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node k = kids.item(i);
            if (k instanceof Element ke) {
                n += elementCount(ke);
            }
        }
        return n;
    }
}
