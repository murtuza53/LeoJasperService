package com.leojasper.rest.controller;

import com.leojasper.rest.config.ServiceConfig;
import com.leojasper.service.ReportGenerationException;
import com.leojasper.service.template.TemplateRegistry;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

@RestController
@RequestMapping("/api/templates")
public class TemplateController {

    private final TemplateRegistry registry;
    private final ServiceConfig.LeoJasperProperties props;

    public TemplateController(TemplateRegistry registry, ServiceConfig.LeoJasperProperties props) {
        this.registry = registry;
        this.props = props;
    }

    @GetMapping
    public List<String> list() throws IOException {
        return registry.list();
    }

    @GetMapping("/{id:.+}")
    public ResponseEntity<byte[]> download(@PathVariable String id) throws IOException {
        if (!registry.exists(id)) {
            return ResponseEntity.notFound().build();
        }
        try (InputStream in = registry.openJrxml(id)) {
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_XML_VALUE);
            headers.add(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"" + sanitize(id) + ".jrxml\"");
            return new ResponseEntity<>(in.readAllBytes(), headers, 200);
        }
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<String> upload(@RequestParam("id") String id,
                                         @RequestParam("file") MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new ReportGenerationException("file is required");
        }
        String safeId = sanitize(id);
        Path target = props.getTemplates().resolved().resolve(safeId + ".jrxml").normalize();
        Path root = props.getTemplates().resolved().toAbsolutePath().normalize();
        if (!target.toAbsolutePath().normalize().startsWith(root)) {
            throw new ReportGenerationException("Template id escapes the templates root");
        }
        Files.createDirectories(target.getParent());
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return ResponseEntity.ok("Saved as " + safeId);
    }

    @DeleteMapping("/{id:.+}")
    public ResponseEntity<Void> delete(@PathVariable String id) throws IOException {
        Path target = props.getTemplates().resolved().resolve(sanitize(id) + ".jrxml").normalize();
        Path root = props.getTemplates().resolved().toAbsolutePath().normalize();
        if (!target.toAbsolutePath().normalize().startsWith(root)) {
            return ResponseEntity.badRequest().build();
        }
        if (Files.deleteIfExists(target)) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.notFound().build();
    }

    private static String sanitize(String id) {
        if (id == null || id.isBlank()) {
            throw new ReportGenerationException("Template id is required");
        }
        // strip extension, keep slashes for nested dirs, strip dangerous segments
        String s = id.endsWith(".jrxml") ? id.substring(0, id.length() - 6) : id;
        if (s.contains("..")) {
            throw new ReportGenerationException("Template id may not contain '..'");
        }
        return s;
    }
}
