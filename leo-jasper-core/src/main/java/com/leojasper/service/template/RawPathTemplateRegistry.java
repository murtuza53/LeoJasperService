package com.leojasper.service.template;

import com.leojasper.service.ReportGenerationException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * No-sandbox registry — the templateId is treated as a literal filesystem path.
 * Used as the default so the legacy {@code generateReport(jrxmlPath, ...)} API
 * keeps working without a configured registry.
 */
public class RawPathTemplateRegistry implements TemplateRegistry {

    @Override
    public InputStream openJrxml(String templatePath) throws IOException {
        Path p = Paths.get(templatePath);
        if (!Files.exists(p)) {
            throw new ReportGenerationException("Template not found: " + templatePath);
        }
        return Files.newInputStream(p);
    }

    @Override
    public boolean exists(String templatePath) {
        try {
            return Files.exists(Paths.get(templatePath));
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public List<String> list() { return List.of(); }

    @Override
    public String cacheKey(String templatePath) throws IOException {
        Path p = Paths.get(templatePath);
        long mtime = Files.exists(p) ? Files.getLastModifiedTime(p).toMillis() : 0L;
        return p.toAbsolutePath().normalize().toString() + "@" + mtime;
    }
}
