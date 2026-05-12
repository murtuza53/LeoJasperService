package com.leojasper.service;

public enum OutputFormat {
    PDF,
    XLS,
    XLSX,
    HTML,
    PNG,
    PNG_ZIP,
    DOCX,
    ODT,
    RTF,
    CSV,
    TEXT,
    PPTX;

    public static OutputFormat from(String value) {
        if (value == null || value.isBlank()) {
            throw new ReportGenerationException("Output format is required");
        }
        switch (value.trim().toLowerCase()) {
            case "pdf":               return PDF;
            case "xls":               return XLS;
            case "xlsx":
            case "excel":             return XLSX;
            case "htm":
            case "html":              return HTML;
            case "png":
            case "img":
            case "image":             return PNG;
            case "png-zip":
            case "pngzip":
            case "png_zip":
            case "images-zip":        return PNG_ZIP;
            case "docx":
            case "word":              return DOCX;
            case "odt":               return ODT;
            case "rtf":               return RTF;
            case "csv":               return CSV;
            case "txt":
            case "text":              return TEXT;
            case "pptx":
            case "powerpoint":        return PPTX;
            default:
                throw new ReportGenerationException("Unsupported output format: " + value);
        }
    }

    public String contentType() {
        switch (this) {
            case PDF:     return "application/pdf";
            case XLS:     return "application/vnd.ms-excel";
            case XLSX:    return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case HTML:    return "text/html;charset=UTF-8";
            case PNG:     return "image/png";
            case PNG_ZIP: return "application/zip";
            case DOCX:    return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case ODT:     return "application/vnd.oasis.opendocument.text";
            case RTF:     return "application/rtf";
            case CSV:     return "text/csv;charset=UTF-8";
            case TEXT:    return "text/plain;charset=UTF-8";
            case PPTX:    return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            default:      return "application/octet-stream";
        }
    }

    public String fileExtension() {
        switch (this) {
            case PDF:     return "pdf";
            case XLS:     return "xls";
            case XLSX:    return "xlsx";
            case HTML:    return "html";
            case PNG:     return "png";
            case PNG_ZIP: return "zip";
            case DOCX:    return "docx";
            case ODT:     return "odt";
            case RTF:     return "rtf";
            case CSV:     return "csv";
            case TEXT:    return "txt";
            case PPTX:    return "pptx";
            default:      return "bin";
        }
    }
}
