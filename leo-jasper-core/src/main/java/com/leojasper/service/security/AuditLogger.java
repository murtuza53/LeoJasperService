package com.leojasper.service.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Append-only audit log per company. One JSON object per line in
 * {@code .meta/audit.jsonl} inside the company folder. Tamper-evident: each
 * line carries the SHA-256 of the previous line so a deletion or modification
 * is detectable on replay.
 */
public class AuditLogger {

    private static final Logger log = LoggerFactory.getLogger(AuditLogger.class);
    private final ObjectMapper jackson = new ObjectMapper();

    public void record(Path companyDir, Map<String, Object> entry) {
        try {
            Path metaDir = companyDir.resolve(".meta");
            Files.createDirectories(metaDir);
            Path file = metaDir.resolve("audit.jsonl");

            Map<String, Object> ordered = new LinkedHashMap<>();
            ordered.put("ts", Instant.now().toString());
            ordered.putAll(entry);
            ordered.put("prevHash", lastLineHash(file));
            String line = jackson.writeValueAsString(ordered) + "\n";
            Files.writeString(file, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("audit write failed for {}: {}", companyDir, e.getMessage());
        }
    }

    private String lastLineHash(Path file) throws IOException {
        if (!Files.exists(file)) return "";
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            if (lines.isEmpty()) return "";
            return PasswordHash.sha256Prefix(lines.get(lines.size() - 1), 16);
        } catch (Exception e) {
            return "";
        }
    }

    /** Read recent audit entries — newest first. */
    public List<Map<String, Object>> recent(Path companyDir, int limit) {
        Path file = companyDir.resolve(".meta").resolve("audit.jsonl");
        if (!Files.exists(file)) return List.of();
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            int from = Math.max(0, lines.size() - Math.max(1, limit));
            java.util.ArrayList<Map<String, Object>> out = new java.util.ArrayList<>();
            for (int i = lines.size() - 1; i >= from; i--) {
                String s = lines.get(i);
                if (s.isBlank()) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> m = jackson.readValue(s, Map.class);
                out.add(m);
            }
            return out;
        } catch (IOException e) {
            log.warn("audit read failed for {}: {}", companyDir, e.getMessage());
            return List.of();
        }
    }
}
