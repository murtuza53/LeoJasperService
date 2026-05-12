package com.leojasper.service.datasource;

import com.leojasper.service.ReportGenerationException;
import net.sf.jasperreports.engine.JRDataSource;
import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.data.JRXmlDataSource;
import org.w3c.dom.Document;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.InputStream;

/**
 * XML factory with XXE protections — parses the document with a hardened
 * {@link DocumentBuilderFactory} (no DOCTYPE, no external entities, no XInclude)
 * and hands the resulting {@link Document} to {@link JRXmlDataSource}.
 */
public class XmlDataSourceFactory implements JRDataSourceFactory {

    @Override public String name() { return "xml"; }

    @Override public boolean supports(Object inputData) { return inputData != null; }

    @Override
    public JRDataSource create(Object inputData) throws JRException, IOException {
        try (InputStream in = InputDataReader.toInputStream(inputData)) {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            try {
                dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
                dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
                dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
                dbf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
                dbf.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
                dbf.setXIncludeAware(false);
                dbf.setExpandEntityReferences(false);
                dbf.setNamespaceAware(true);
            } catch (ParserConfigurationException | IllegalArgumentException e) {
                throw new ReportGenerationException("Cannot harden XML parser: " + e.getMessage(), e);
            }
            try {
                DocumentBuilder builder = dbf.newDocumentBuilder();
                Document document = builder.parse(in);
                return new JRXmlDataSource(document);
            } catch (Exception e) {
                throw new ReportGenerationException("Failed to parse XML input: " + e.getMessage(), e);
            }
        }
    }
}
