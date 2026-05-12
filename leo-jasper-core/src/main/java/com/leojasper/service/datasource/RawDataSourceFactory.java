package com.leojasper.service.datasource;

import com.leojasper.service.ReportGenerationException;
import net.sf.jasperreports.engine.JRDataSource;

public class RawDataSourceFactory implements JRDataSourceFactory {

    @Override public String name() { return "raw"; }

    @Override public boolean supports(Object inputData) { return inputData instanceof JRDataSource; }

    @Override
    public JRDataSource create(Object inputData) {
        if (inputData instanceof JRDataSource) {
            return (JRDataSource) inputData;
        }
        throw new ReportGenerationException(
                "RAW factory requires a JRDataSource, got " +
                        (inputData == null ? "null" : inputData.getClass().getName()));
    }
}
