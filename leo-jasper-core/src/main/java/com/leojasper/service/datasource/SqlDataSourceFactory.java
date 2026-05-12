package com.leojasper.service.datasource;

import com.leojasper.service.ReportGenerationException;
import net.sf.jasperreports.engine.JRDataSource;
import net.sf.jasperreports.engine.JRResultSetDataSource;

import java.sql.Connection;
import java.sql.ResultSet;

/**
 * SQL factory — only handles {@link ResultSet} here. {@link Connection} is
 * special-cased upstream so JasperReports can run the report's own queryString.
 */
public class SqlDataSourceFactory implements JRDataSourceFactory {

    @Override public String name() { return "sql"; }

    @Override public boolean supports(Object inputData) {
        return inputData instanceof Connection || inputData instanceof ResultSet;
    }

    @Override
    public JRDataSource create(Object inputData) {
        if (inputData instanceof ResultSet) {
            return new JRResultSetDataSource((ResultSet) inputData);
        }
        throw new ReportGenerationException(
                "SQL factory cannot build a data source from " +
                        (inputData == null ? "null" : inputData.getClass().getName()) +
                        " — pass a Connection (handled by service) or a ResultSet");
    }
}
