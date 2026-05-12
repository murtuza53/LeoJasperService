package com.leojasper.service.datasource;

import net.sf.jasperreports.engine.JRDataSource;
import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.data.JRCsvDataSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class CsvDataSourceFactory implements JRDataSourceFactory {
    @Override public String name() { return "csv"; }

    @Override public boolean supports(Object inputData) { return inputData != null; }

    @Override
    public JRDataSource create(Object inputData) throws JRException, IOException {
        JRCsvDataSource csv = new JRCsvDataSource(
                InputDataReader.toInputStream(inputData), StandardCharsets.UTF_8.name());
        csv.setUseFirstRowAsHeader(true);
        return csv;
    }
}
