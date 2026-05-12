package com.leojasper.service.datasource;

import com.leojasper.service.ReportGenerationException;
import net.sf.jasperreports.engine.JRDataSource;
import net.sf.jasperreports.engine.data.JRBeanCollectionDataSource;

import java.util.Collection;

public class BeansDataSourceFactory implements JRDataSourceFactory {

    @Override public String name() { return "beans"; }

    @Override public boolean supports(Object inputData) {
        return inputData instanceof Collection<?>;
    }

    @Override
    public JRDataSource create(Object inputData) {
        if (inputData instanceof Collection<?>) {
            return new JRBeanCollectionDataSource((Collection<?>) inputData);
        }
        throw new ReportGenerationException(
                "BEANS factory requires a java.util.Collection, got " +
                        (inputData == null ? "null" : inputData.getClass().getName()));
    }
}
