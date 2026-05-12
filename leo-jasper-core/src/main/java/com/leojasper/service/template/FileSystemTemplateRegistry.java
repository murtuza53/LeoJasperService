package com.leojasper.service.template;

import com.leojasper.service.ReportGenerationException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Filesystem-backed registry. Templates are looked up under a root directory by
 * id, with {@code .jrxml} appended automatically. Cache keys include the file's
 * last-modified timestamp so edits hot-reload the compiled report cache.
 */
public class FileSystemTemplateRegistry implements TemplateRegistry {

    private final Path root;

    public FileSystemTemplateRegistry(Path root) {
        if (root == null) {
            throw new ReportGenerationException("Template registry root is null");
        }
        this.root = root.toAbsolutePath().normalize();
    }

    private Path resolve(String templateId) {
        if (templateId == null || templateId.isBlank()) {
            throw new ReportGenerationException("templateId is required");
        }
        // strip extension so callers can pass either "invoice" or "invoice.jrxml"
        String id = templateId.endsWith(".jrxml")
                ? templateId.substring(0, templateId.length() - ".jrxml".length())
                : templateId;
        Path resolved = root.resolve(id + ".jrxml").normalize();
        if (!resolved.startsWith(root)) {
            throw new ReportGenerationException(
                    "templateId escapes the registry root: " + templateId);
        }
        return resolved;
    }

    @Override
    public InputStream openJrxml(String templateId) throws IOException {
        Path p = resolve(templateId);
        if (!Files.exists(p)) {
            throw new ReportGenerationException("Template not found: " + templateId);
        }
        return Files.newInputStream(p);
    }

    @Override
    public boolean exists(String templateId) {
        try {
            return Files.exists(resolve(templateId));
        } catch (ReportGenerationException e) {
            return false;
        }
    }

    @Override
    public List<String> list() throws IOException {
        if (!Files.isDirectory(root)) return List.of();
        try (Stream<Path> walk = Files.walk(root)) {
            return walk
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().toLowerCase().endsWith(".jrxml"))
                    .map(root::relativize)
                    .map(p -> p.toString().replace('\\', '/'))
                    .map(s -> s.substring(0, s.length() - ".jrxml".length()))
                    .sorted()
                    .collect(Collectors.toList());
        }
    }

    @Override
    public String cacheKey(String templateId) throws IOException {
        Path p = resolve(templateId);
        long mtime = Files.exists(p) ? Files.getLastModifiedTime(p).toMillis() : 0L;
        return p.toString() + "@" + mtime;
    }
}
