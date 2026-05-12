package com.leojasper.service.template;

import com.leojasper.service.ReportGenerationException;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

public class ClasspathTemplateRegistry implements TemplateRegistry {

    private final String basePath;
    private final ClassLoader classLoader;

    public ClasspathTemplateRegistry(String basePath) {
        this(basePath, Thread.currentThread().getContextClassLoader());
    }

    public ClasspathTemplateRegistry(String basePath, ClassLoader classLoader) {
        this.basePath = basePath == null ? "" : basePath.replaceAll("/+$", "") + "/";
        this.classLoader = classLoader == null
                ? ClasspathTemplateRegistry.class.getClassLoader()
                : classLoader;
    }

    private String resourcePath(String templateId) {
        if (templateId == null || templateId.isBlank()) {
            throw new ReportGenerationException("templateId is required");
        }
        String id = templateId.endsWith(".jrxml")
                ? templateId
                : templateId + ".jrxml";
        return basePath + id;
    }

    @Override
    public InputStream openJrxml(String templateId) throws IOException {
        String path = resourcePath(templateId);
        InputStream in = classLoader.getResourceAsStream(path);
        if (in == null) {
            throw new ReportGenerationException("Template not found on classpath: " + path);
        }
        return in;
    }

    @Override
    public boolean exists(String templateId) {
        return classLoader.getResource(resourcePath(templateId)) != null;
    }

    @Override
    public List<String> list() {
        // Classpath enumeration is unreliable across packagings — leave empty.
        return List.of();
    }
}
