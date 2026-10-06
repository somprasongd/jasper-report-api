package com.github.somprasongd.jasperreport.api.jrxml;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * JasperReports classes that JR 7 moved to another package when it split the engine into modules (data sources to
 * {@code json}/{@code poi}, the PDF exporter to {@code pdf}, charts to {@code charts}, ...) or removed (the old renderer and
 * exporter-parameter classes), found by comparing the 6.21.5 jar with the 7.0.8 modules. A report expression that names one
 * of them by its old name does not compile on JR 7.
 */
final class RelocatedClasses {

    private static final String PREFIX = "net.sf.jasperreports.";

    /** The JR 7 module (jar) that holds the classes of a package below {@code net.sf.jasperreports}. */
    private static final Map<String, String> MODULES = Map.ofEntries(Map.entry("engine", "jasperreports"), Map.entry("jackson", "jasperreports"),
            Map.entry("json", "jasperreports-json"), Map.entry("pdf", "jasperreports-pdf"), Map.entry("barcode4j", "jasperreports-barcode4j"),
            Map.entry("groovy", "jasperreports-groovy"), Map.entry("javascript", "jasperreports-javascript"), Map.entry("charts", "jasperreports-charts"),
            Map.entry("poi", "jasperreports-excel-poi"), Map.entry("dataadapters", "jasperreports-data-adapters"),
            Map.entry("hibernate", "jasperreports-hibernate"), Map.entry("jaxen", "jasperreports-jaxen"), Map.entry("barbecue", "jasperreports-barbecue"));

    /** The modules this API ships; the rest has to be added to the class path. */
    private static final Set<String> SHIPPED = Set.of("jasperreports", "jasperreports-json", "jasperreports-pdf",
            "jasperreports-barcode4j", "jasperreports-groovy", "jasperreports-fonts");

    /** Classes that JR 7 no longer has at all. */
    private static final Set<String> REMOVED = new HashSet<>();

    private static final Map<String, String> MOVED = new HashMap<>();

    static {
        String table = """
            data.AbstractClasspathAwareDataAdapter dataadapters.AbstractClasspathAwareDataAdapter
            data.AbstractClasspathAwareDataAdapterService dataadapters.AbstractClasspathAwareDataAdapterService
            data.AbstractDataAdapter dataadapters.AbstractDataAdapter
            data.AbstractDataAdapterService dataadapters.AbstractDataAdapterService
            data.BuiltinDataFileServiceFactory dataadapters.BuiltinDataFileServiceFactory
            data.ClasspathAwareDataAdapter dataadapters.ClasspathAwareDataAdapter
            data.DataAdapter dataadapters.DataAdapter
            data.DataAdapterContributorFactory dataadapters.DataAdapterContributorFactory
            data.DataAdapterParameterContributorExtensionsRegistryFactory dataadapters.DataAdapterParameterContributorExtensionsRegistryFactory
            data.DataAdapterParameterContributorFactory dataadapters.DataAdapterParameterContributorFactory
            data.DataAdapterService dataadapters.DataAdapterService
            data.DataAdapterServiceUtil dataadapters.DataAdapterServiceUtil
            data.DataFileConnection dataadapters.DataFileConnection
            data.DataFileResolver dataadapters.DataFileResolver
            data.DataFileService dataadapters.DataFileService
            data.DataFileServiceFactory dataadapters.DataFileServiceFactory
            data.DataFileStream dataadapters.DataFileStream
            data.DataFileStreamConnection dataadapters.DataFileStreamConnection
            data.DataFileUtils dataadapters.DataFileUtils
            data.DataSourceCollection engine.data.DataSourceCollection
            data.DataSourceProvider engine.data.DataSourceProvider
            data.DefaultDataAdapterServiceExtensionsRegistryFactory dataadapters.DefaultDataAdapterServiceExtensionsRegistryFactory
            data.DefaultDataAdapterServiceFactory dataadapters.DefaultDataAdapterServiceFactory
            data.FileDataAdapter dataadapters.FileDataAdapter
            data.RepositoryDataLocation dataadapters.RepositoryDataLocation
            data.RepositoryDataLocationService dataadapters.RepositoryDataLocationService
            data.RewindableDataSourceCollection engine.data.RewindableDataSourceCollection
            data.RewindableDataSourceProvider engine.data.RewindableDataSourceProvider
            data.ejbql.EjbqlDataAdapterService jakarta.ejbql.EjbqlDataAdapterService
            data.excel.ExcelDataAdapterService poi.data.ExcelDataAdapterService
            data.hibernate.HibernateDataAdapterService hibernate.HibernateDataAdapterService
            data.hibernate.spring.SpringHibernateDataAdapterService hibernate.SpringHibernateDataAdapterService
            data.xls.XlsDataAdapterService poi.data.XlsDataAdapterService
            data.xlsx.XlsxDataAdapterService poi.data.XlsxDataAdapterService
            engine.JRAbstractChartCustomizer charts.JRAbstractChartCustomizer
            engine.JRChart charts.JRChart
            engine.JRChartCustomizer charts.JRChartCustomizer
            engine.JRChartDataset charts.JRChartDataset
            engine.JRChartPlot charts.JRChartPlot
            engine.NamedChartCustomizer charts.NamedChartCustomizer
            engine.Renderable renderers.Renderable
            engine.convert.ChartConverter charts.convert.ChartConverter
            engine.convert.ConvertChartContext charts.convert.ConvertChartContext
            engine.data.AbstractPoiXlsDataSource poi.data.AbstractPoiXlsDataSource
            engine.data.ExcelDataSource poi.data.ExcelDataSource
            engine.data.ExcelDataSourceFactory poi.data.ExcelDataSourceFactory
            engine.data.JRHibernateAbstractDataSource hibernate.JRHibernateAbstractDataSource
            engine.data.JRHibernateIterateDataSource hibernate.JRHibernateIterateDataSource
            engine.data.JRHibernateListDataSource hibernate.JRHibernateListDataSource
            engine.data.JRHibernateScrollDataSource hibernate.JRHibernateScrollDataSource
            engine.data.JRJpaDataSource jakarta.ejbql.JRJpaDataSource
            engine.data.JRXlsxDataSource poi.data.JRXlsxDataSource
            engine.data.JaxenXmlDataSource jaxen.data.JaxenXmlDataSource
            engine.data.JsonDataSource json.data.JsonDataSource
            engine.data.JsonDataSourceProvider json.data.JsonDataSourceProvider
            engine.data.JsonQLDataSource json.data.JsonQLDataSource
            engine.data.JsonQLDataSourceProvider json.data.JsonQLDataSourceProvider
            engine.data.XlsDataSource poi.data.XlsDataSource
            engine.export.AbstractPdfTextRenderer pdf.AbstractPdfTextRenderer
            engine.export.GenericElementJsonHandler json.export.GenericElementJsonHandler
            engine.export.GenericElementPdfHandler pdf.GenericElementPdfHandler
            engine.export.GenericElementXlsHandler poi.export.GenericElementXlsHandler
            engine.export.GenericElementXlsMetadataHandler poi.export.GenericElementXlsMetadataHandler
            engine.export.ImageSettings poi.export.ImageSettings
            engine.export.JRPdfExporter pdf.JRPdfExporter
            engine.export.JRPdfExporterContext pdf.JRPdfExporterContext
            engine.export.JRPdfExporterTagHelper pdf.JRPdfExporterTagHelper
            engine.export.JRXlsExporter poi.export.JRXlsExporter
            engine.export.JRXlsExporterContext poi.export.JRXlsExporterContext
            engine.export.JRXlsExporterNature poi.export.JRXlsExporterNature
            engine.export.JRXlsMetadataExporter poi.export.JRXlsMetadataExporter
            engine.export.JRXlsMetadataExporterNature poi.export.JRXlsMetadataExporterNature
            engine.export.JsonExporter json.export.JsonExporter
            engine.export.JsonExporterContext json.export.JsonExporterContext
            engine.export.JsonMetadataExporter json.export.JsonMetadataExporter
            engine.export.LineBreaksPdfTextRenderer pdf.LineBreaksPdfTextRenderer
            engine.export.PdfGlyphGraphics2D pdf.PdfGlyphGraphics2D
            engine.export.PdfGlyphRenderer pdf.PdfGlyphRenderer
            engine.export.PdfTextRenderer pdf.PdfTextRenderer
            engine.export.PdfXmpCreator pdf.PdfXmpCreator
            engine.export.SimpleAbstractPdfTextRenderer pdf.SimpleAbstractPdfTextRenderer
            engine.export.SimplePdfTextRenderer pdf.SimplePdfTextRenderer
            engine.export.XmpWriter pdf.XmpWriter
            engine.export.type.PdfFieldBorderStyleEnum pdf.type.PdfFieldBorderStyleEnum
            engine.export.type.PdfFieldCheckTypeEnum pdf.type.PdfFieldCheckTypeEnum
            engine.export.type.PdfFieldTypeEnum pdf.type.PdfFieldTypeEnum
            engine.query.AbstractJsonQueryExecuter json.query.AbstractJsonQueryExecuter
            engine.query.ExcelQueryExecuter poi.query.ExcelQueryExecuter
            engine.query.ExcelQueryExecuterFactory poi.query.ExcelQueryExecuterFactory
            engine.query.JRHibernateQueryExecuter hibernate.JRHibernateQueryExecuter
            engine.query.JRHibernateQueryExecuterFactory hibernate.JRHibernateQueryExecuterFactory
            engine.query.JRJpaQueryExecuter jakarta.ejbql.JRJpaQueryExecuter
            engine.query.JRJpaQueryExecuterFactory jakarta.ejbql.JRJpaQueryExecuterFactory
            engine.query.JRXlsxQueryExecuter poi.query.JRXlsxQueryExecuter
            engine.query.JRXlsxQueryExecuterFactory poi.query.JRXlsxQueryExecuterFactory
            engine.query.JaxenXPathQueryExecuter jaxen.query.JaxenXPathQueryExecuter
            engine.query.JaxenXPathQueryExecuterFactory jaxen.query.JaxenXPathQueryExecuterFactory
            engine.query.JsonQLQueryExecuter json.query.JsonQLQueryExecuter
            engine.query.JsonQLQueryExecuterFactory json.query.JsonQLQueryExecuterFactory
            engine.query.JsonQueryExecuter json.query.JsonQueryExecuter
            engine.query.JsonQueryExecuterFactory json.query.JsonQueryExecuterFactory
            engine.query.XlsQueryExecuter poi.query.XlsQueryExecuter
            engine.query.XlsQueryExecuterFactory poi.query.XlsQueryExecuterFactory
            engine.util.BreakIteratorSplitCharacter pdf.util.BreakIteratorSplitCharacter
            engine.util.JRPdfaIccProfileNotFoundException pdf.util.JRPdfaIccProfileNotFoundException
            engine.util.JsonUtil json.util.JsonUtil
            engine.util.json.DefaultJsonQLExecuter json.util.DefaultJsonQLExecuter
            engine.util.json.JsonQLExecuter json.util.JsonQLExecuter
            engine.util.xml.JaxenNsAwareXPathExecuter jaxen.util.xml.JaxenNsAwareXPathExecuter
            engine.util.xml.JaxenXPathExecuter jaxen.util.xml.JaxenXPathExecuter
            engine.util.xml.JaxenXPathExecuterFactory jaxen.util.xml.JaxenXPathExecuterFactory
            export.JsonExporterConfiguration json.export.JsonExporterConfiguration
            export.JsonExporterOutput json.export.JsonExporterOutput
            export.JsonMetadataReportConfiguration json.export.JsonMetadataReportConfiguration
            export.JsonReportConfiguration json.export.JsonReportConfiguration
            export.PdfExporterConfiguration pdf.PdfExporterConfiguration
            export.PdfReportConfiguration pdf.PdfReportConfiguration
            export.SimpleJsonExporterConfiguration json.export.SimpleJsonExporterConfiguration
            export.SimpleJsonExporterOutput json.export.SimpleJsonExporterOutput
            export.SimpleJsonMetadataReportConfiguration json.export.SimpleJsonMetadataReportConfiguration
            export.SimpleJsonReportConfiguration json.export.SimpleJsonReportConfiguration
            export.SimplePdfExporterConfiguration pdf.SimplePdfExporterConfiguration
            export.SimplePdfReportConfiguration pdf.SimplePdfReportConfiguration
            export.pdf.FontRecipient pdf.common.FontRecipient
            export.pdf.LineCapStyle pdf.common.LineCapStyle
            export.pdf.PdfChunk pdf.common.PdfChunk
            export.pdf.PdfContent pdf.common.PdfContent
            export.pdf.PdfDocument pdf.common.PdfDocument
            export.pdf.PdfDocumentWriter pdf.common.PdfDocumentWriter
            export.pdf.PdfField pdf.common.PdfField
            export.pdf.PdfFontStyle pdf.common.PdfFontStyle
            export.pdf.PdfImage pdf.common.PdfImage
            export.pdf.PdfOutlineEntry pdf.common.PdfOutlineEntry
            export.pdf.PdfPhrase pdf.common.PdfPhrase
            export.pdf.PdfProducer pdf.common.PdfProducer
            export.pdf.PdfProducerContext pdf.common.PdfProducerContext
            export.pdf.PdfProducerFactory pdf.common.PdfProducerFactory
            export.pdf.PdfRadioCheck pdf.common.PdfRadioCheck
            export.pdf.PdfStructure pdf.common.PdfStructure
            export.pdf.PdfStructureEntry pdf.common.PdfStructureEntry
            export.pdf.PdfTextAlignment pdf.common.PdfTextAlignment
            export.pdf.PdfTextChunk pdf.common.PdfTextChunk
            export.pdf.PdfTextField pdf.common.PdfTextField
            export.pdf.PdfTextRendererContext pdf.common.PdfTextRendererContext
            export.pdf.TextDirection pdf.common.TextDirection
            export.pdf.classic.ClassicChunk pdf.classic.ClassicChunk
            export.pdf.classic.ClassicDocument pdf.classic.ClassicDocument
            export.pdf.classic.ClassicFontRecipient pdf.classic.ClassicFontRecipient
            export.pdf.classic.ClassicImage pdf.classic.ClassicImage
            export.pdf.classic.ClassicPdfContent pdf.classic.ClassicPdfContent
            export.pdf.classic.ClassicPdfField pdf.classic.ClassicPdfField
            export.pdf.classic.ClassicPdfFontMapper pdf.classic.ClassicPdfFontMapper
            export.pdf.classic.ClassicPdfOutline pdf.classic.ClassicPdfOutline
            export.pdf.classic.ClassicPdfProducer pdf.classic.ClassicPdfProducer
            export.pdf.classic.ClassicPdfProducerFactory pdf.classic.ClassicPdfProducerFactory
            export.pdf.classic.ClassicPdfStructure pdf.classic.ClassicPdfStructure
            export.pdf.classic.ClassicPdfTextField pdf.classic.ClassicPdfTextField
            export.pdf.classic.ClassicPdfUtils pdf.classic.ClassicPdfUtils
            export.pdf.classic.ClassicPdfWriter pdf.classic.ClassicPdfWriter
            export.pdf.classic.ClassicPhrase pdf.classic.ClassicPhrase
            export.pdf.classic.ClassicRadioCheck pdf.classic.ClassicRadioCheck
            export.pdf.classic.ClassicStructureEntry pdf.classic.ClassicStructureEntry
            export.pdf.classic.ClassicTextChunk pdf.classic.ClassicTextChunk
            export.pdf.classic.GlyphRendering pdf.classic.GlyphRendering
            export.pdf.classic.PatchedPdfLibraryUnavailableException pdf.classic.PatchedPdfLibraryUnavailableException
            export.type.PdfPermissionsEnum pdf.type.PdfPermissionsEnum
            export.type.PdfPrintScalingEnum pdf.type.PdfPrintScalingEnum
            export.type.PdfVersionEnum pdf.type.PdfVersionEnum
            export.type.PdfaConformanceEnum pdf.type.PdfaConformanceEnum
            renderers.JCommonDrawableRendererImpl charts.renderers.JCommonDrawableRendererImpl
            """;
        String removed = """
            engine.ImageMapRenderable engine.JRAbstractRenderer engine.JRAbstractSvgRenderer engine.JRExporter engine.JRExporterParameter
            engine.JRImageMapRenderer engine.JRImageRenderer engine.JRRenderable engine.JRWrappingSvgRenderer engine.RenderableUtil
            engine.export.JRCsvExporterParameter engine.export.JRCsvMetadataExporterParameter engine.export.JRGraphics2DExporterParameter
            engine.export.JRHtmlExporterParameter engine.export.JRPdfExporterParameter engine.export.JRPrintServiceExporterParameter
            engine.export.JRTextExporterParameter engine.export.JRXlsAbstractExporterParameter engine.export.JRXlsAbstractMetadataExporterParameter
            engine.export.JRXlsExporterParameter engine.export.JRXmlExporterParameter engine.export.JsonExporterParameter
            engine.type.HorizontalAlignEnum engine.type.RenderableTypeEnum engine.type.VerticalAlignEnum engine.util.FileResolver
            engine.util.SimpleFileResolver
            """;
        for (String name : removed.trim().split("\\s+")) {
            REMOVED.add(PREFIX + name);
        }
        for (String line : table.split("\n")) {
            String[] pair = line.trim().split(" ");
            if (pair.length == 2) {
                MOVED.put(PREFIX + pair[0], PREFIX + pair[1]);
            }
        }
    }

    private RelocatedClasses() {
    }

    /** A class named by a report and the name JR 7 gives it ({@code to} is {@code null} when JR 7 no longer has it). */
    record Moved(String from, String to) {
    }

    /** The moved or removed class that {@code fqn} (a class name, possibly followed by {@code .member} text) starts with, or {@code null}. */
    static Moved find(String fqn) {
        for (String candidate = fqn; candidate.length() > PREFIX.length(); candidate = candidate.substring(0, candidate.lastIndexOf('.'))) {
            String moved = MOVED.get(candidate);
            if (moved != null) {
                return new Moved(candidate, moved);
            }
            if (REMOVED.contains(candidate)) {
                return new Moved(candidate, null);
            }
            if (candidate.lastIndexOf('.') < 0) {
                break;
            }
        }
        return null;
    }

    /** Where {@code newName} lives: its module and whether this API ships it. */
    static String module(String newName) {
        String rest = newName.substring(PREFIX.length());
        String pkg = rest.substring(0, rest.indexOf('.'));
        String module = MODULES.get(pkg);
        if (module == null) {
            return "package " + pkg;
        }
        return "module " + module + (SHIPPED.contains(module) ? ", included in this API" : ", which this API does not include");
    }
}
