package com.leojasper.service;

import com.leojasper.service.template.ClasspathTemplateRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeoJasperServiceTest {

    private static Path tplPath;

    @BeforeAll
    static void materialiseTemplate() throws IOException {
        tplPath = Files.createTempFile("sample-", ".jrxml");
        try (InputStream in = new ClasspathTemplateRegistry("templates").openJrxml("sample")) {
            Files.copy(in, tplPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Test
    @DisplayName("JSON input → PDF starts with %PDF marker")
    void jsonToPdf() {
        String json = "[{\"name\":\"Alpha\",\"qty\":3},{\"name\":\"Bravo\",\"qty\":7}]";
        byte[] pdf = new LeoJasperService()
                .generateReport(tplPath.toString(), json, "json", "pdf");
        assertNotNull(pdf);
        assertTrue(pdf.length > 100, "PDF should be non-trivial");
        assertEquals('%', (char) pdf[0]);
        assertEquals('P', (char) pdf[1]);
        assertEquals('D', (char) pdf[2]);
        assertEquals('F', (char) pdf[3]);
    }

    @Test
    @DisplayName("CSV input → XLSX starts with the ZIP magic bytes")
    void csvToXlsx() {
        byte[] csv = "name,qty\nAlpha,3\nBravo,7\n".getBytes();
        byte[] xlsx = new LeoJasperService()
                .generateReport(tplPath.toString(), csv, "csv", "xlsx");
        // XLSX is a zip — starts with PK\003\004
        assertEquals('P', (char) xlsx[0]);
        assertEquals('K', (char) xlsx[1]);
        assertEquals(3, xlsx[2]);
        assertEquals(4, xlsx[3]);
    }

    @Test
    @DisplayName("Bean collection → HTML contains the row data")
    void beansToHtml() {
        List<Item> items = new ArrayList<>();
        items.add(new Item("Alpha", 3));
        items.add(new Item("Bravo", 7));

        byte[] html = new LeoJasperService()
                .generateReport(tplPath.toString(), items, "beans", "html");
        String body = new String(html, java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(body.contains("Alpha"), "HTML should contain 'Alpha'");
        assertTrue(body.contains("Bravo"), "HTML should contain 'Bravo'");
    }

    @Test
    @DisplayName("Multi-page PNG returns at least one image per page")
    void multiPagePng() {
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < 80; i++) items.add(new Item("Row " + i, i));

        List<byte[]> pages = new LeoJasperService()
                .generateReportImages(tplPath.toString(), items, "beans", null, 1.0f);
        assertTrue(pages.size() >= 1, "Expected at least one page");
        for (byte[] png : pages) {
            // PNG signature: 89 50 4E 47 0D 0A 1A 0A
            assertEquals((byte) 0x89, png[0]);
            assertEquals((byte) 0x50, png[1]);
            assertEquals((byte) 0x4E, png[2]);
            assertEquals((byte) 0x47, png[3]);
        }
    }

    @Test
    @DisplayName("Unsupported output format throws ReportGenerationException")
    void unsupportedFormat() {
        assertThrows(ReportGenerationException.class,
                () -> new LeoJasperService().generateReport(
                        tplPath.toString(), "[]", "json", "tiff"));
    }

    @Test
    @DisplayName("All supported output formats produce non-empty bytes")
    void allOutputFormats() {
        List<Item> items = List.of(new Item("Alpha", 1), new Item("Bravo", 2));
        String[] formats = {
                "pdf", "xlsx", "xls", "html", "png", "png-zip",
                "docx", "odt", "rtf", "csv", "text", "pptx"
        };
        LeoJasperService svc = new LeoJasperService();
        for (String fmt : formats) {
            byte[] out = svc.generateReport(tplPath.toString(), items, "beans", fmt);
            assertTrue(out.length > 0, "Format " + fmt + " produced empty output");
        }
    }

    public static class Item {
        private final String name;
        private final int qty;
        public Item(String name, int qty) { this.name = name; this.qty = qty; }
        public String getName() { return name; }
        public int getQty() { return qty; }
    }
}
