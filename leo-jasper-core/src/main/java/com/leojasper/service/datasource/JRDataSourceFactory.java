package com.leojasper.service.datasource;

import net.sf.jasperreports.engine.JRDataSource;
import net.sf.jasperreports.engine.JRException;

import java.io.IOException;

/**
 * SPI for plugging in custom data source adapters. Built-in factories ship for
 * JSON / XML / CSV / SQL / BEANS — third parties register their own via
 * {@code META-INF/services/com.leojasper.service.datasource.JRDataSourceFactory}.
 */
public interface JRDataSourceFactory {

    /** Lower-case, hyphen-free format name. Matched against the {@code inputFormat} hint. */
    String name();

    /** True if this factory can build a data source from the given input. */
    boolean supports(Object inputData);

    JRDataSource create(Object inputData) throws JRException, IOException;
}
