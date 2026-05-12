package com.leojasper.service;

public enum InputFormat {
    JSON,
    /**
     * Single JSON file with two top-level sections:
     * <pre>{
     *   "parameters": { "customer.code": "C0002", "totals.subTotal": 46.0, ... },
     *   "data":       [ { "lineNumber": 1, ... }, { "lineNumber": 2, ... } ]
     * }</pre>
     * The {@code parameters} object is merged into the report's parameter map
     * (caller-supplied parameters take precedence). The {@code data} array is
     * fed to the same {@code JsonDataSource} as the plain {@link #JSON} format,
     * so detail-band fields work identically.
     */
    JSON_DOC,
    XML,
    CSV,
    SQL,
    BEANS,
    RAW;

    public static InputFormat from(String value) {
        if (value == null || value.isBlank()) {
            throw new ReportGenerationException("Input format is required");
        }
        switch (value.trim().toLowerCase()) {
            case "json":              return JSON;
            case "json-doc":
            case "jsondoc":
            case "json-document":
            case "json-pd":           return JSON_DOC;
            case "xml":               return XML;
            case "csv":               return CSV;
            case "sql":
            case "jdbc":              return SQL;
            case "bean":
            case "beans":
            case "javabean":
            case "javabeans":
            case "pojo":              return BEANS;
            case "raw":
            case "datasource":        return RAW;
            default:
                throw new ReportGenerationException("Unsupported input format: " + value);
        }
    }
}
