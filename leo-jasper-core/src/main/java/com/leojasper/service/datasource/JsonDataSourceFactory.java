package com.leojasper.service.datasource;

import net.sf.jasperreports.engine.JRDataSource;
import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.data.JsonDataSource;

import java.io.IOException;

public class JsonDataSourceFactory implements JRDataSourceFactory {
    @Override public String name() { return "json"; }

    @Override public boolean supports(Object inputData) {
        return inputData != null;
    }

    @Override
    public JRDataSource create(Object inputData) throws JRException, IOException {
        return new JsonDataSource(InputDataReader.toInputStream(inputData));
    }
}
