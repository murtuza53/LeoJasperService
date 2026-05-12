package com.leojasper.service.synthesis;

import com.leojasper.service.ReportGenerationException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Per-template asset directory: {root}/{templateId}/{name}.
 * Used to store rendered page previews and extracted images so the user
 * (or the JRXML) can refer to them later.
 */
public class AssetStore {

    private final Path root;

    public AssetStore(Path root) {
        if (root == null) throw new ReportGenerationException("Asset store root is null");
        this.root = root.toAbsolutePath().normalize();
    }

    public Path templateDir(String templateId) {
        validateId(templateId);
        Path dir = root.resolve(templateId).normalize();
        if (!dir.startsWith(root)) {
            throw new ReportGenerationException("templateId escapes asset root: " + templateId);
        }
        return dir;
    }

    public Path save(String templateId, String name, byte[] bytes) {
        validateName(name);
        try {
            Path dir = templateDir(templateId);
            Files.createDirectories(dir);
            Path target = dir.resolve(name).normalize();
            if (!target.startsWith(dir)) {
                throw new ReportGenerationException("Asset name escapes template dir: " + name);
            }
            Files.write(target, bytes);
            return target;
        } catch (IOException e) {
            throw new ReportGenerationException(
                    "Failed to save asset " + name + ": " + e.getMessage(), e);
        }
    }

    public List<String> list(String templateId) {
        Path dir = templateDir(templateId);
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> walk = Files.list(dir)) {
            List<String> out = new ArrayList<>();
            walk.filter(Files::isRegularFile)
                .map(p -> dir.relativize(p).toString().replace('\\', '/'))
                .sorted()
                .forEach(out::add);
            return out;
        } catch (IOException e) {
            throw new ReportGenerationException(
                    "Failed to list assets for " + templateId + ": " + e.getMessage(), e);
        }
    }

    public InputStream open(String templateId, String name) {
        validateName(name);
        Path target = templateDir(templateId).resolve(name).normalize();
        Path dir = templateDir(templateId);
        if (!target.startsWith(dir)) {
            throw new ReportGenerationException("Asset name escapes template dir: " + name);
        }
        if (!Files.exists(target)) {
            throw new ReportGenerationException("Asset not found: " + templateId + "/" + name);
        }
        try { return Files.newInputStream(target); }
        catch (IOException e) {
            throw new ReportGenerationException(
                    "Failed to open asset: " + e.getMessage(), e);
        }
    }

    public boolean delete(String templateId, String name) {
        validateName(name);
        try {
            Path target = templateDir(templateId).resolve(name).normalize();
            return Files.deleteIfExists(target);
        } catch (IOException e) {
            throw new ReportGenerationException(
                    "Failed to delete asset: " + e.getMessage(), e);
        }
    }

    public void copyInto(String templateId, String name, InputStream in) {
        validateName(name);
        try {
            Path dir = templateDir(templateId);
            Files.createDirectories(dir);
            Path target = dir.resolve(name).normalize();
            if (!target.startsWith(dir)) {
                throw new ReportGenerationException("Asset name escapes template dir: " + name);
            }
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new ReportGenerationException(
                    "Failed to copy asset: " + e.getMessage(), e);
        }
    }

    private void validateId(String id) {
        if (id == null || id.isBlank()) {
            throw new ReportGenerationException("templateId is required");
        }
        if (id.contains("..") || id.contains("\\")) {
            throw new ReportGenerationException("Invalid templateId: " + id);
        }
    }

    private void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new ReportGenerationException("Asset name is required");
        }
        if (name.contains("..") || name.contains("/") || name.contains("\\")) {
            throw new ReportGenerationException("Invalid asset name: " + name);
        }
    }
}
