package com.leojasper.service.security;

import com.leojasper.service.ReportGenerationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Per-company asset CRUD + soft-delete with restore window. Buckets are a
 * fixed set so a stolen license can't pollute arbitrary paths.
 */
public class AssetService {

    private static final Logger log = LoggerFactory.getLogger(AssetService.class);

    public static final Set<String> BUCKETS = Set.of("images", "templates", "data", "other");

    private final Duration trashRetention;

    public AssetService() { this(Duration.ofDays(30)); }
    public AssetService(Duration trashRetention) { this.trashRetention = trashRetention; }

    public Duration trashRetention() { return trashRetention; }

    /**
     * Create the standard sub-folders inside the company dir if they don't
     * exist. Idempotent. Called during company registration.
     */
    public void initLayout(Path companyDir) {
        try {
            Files.createDirectories(companyDir);
            for (String b : BUCKETS) Files.createDirectories(companyDir.resolve(b));
            Files.createDirectories(companyDir.resolve(".meta").resolve("trash"));
        } catch (IOException e) {
            throw new ReportGenerationException(
                    "Failed to init company folders: " + e.getMessage(), e);
        }
    }

    private void requireValidBucket(String bucket) {
        if (!BUCKETS.contains(bucket)) {
            throw new ReportGenerationException(
                    "Unknown bucket: " + bucket + " — allowed: " + BUCKETS);
        }
    }

    private Path resolveSafe(Path companyDir, String bucket, String name) {
        requireValidBucket(bucket);
        if (name == null || name.isBlank()) {
            throw new ReportGenerationException("Asset name is required");
        }
        if (name.contains("..") || name.contains("/") || name.contains("\\")) {
            throw new ReportGenerationException("Invalid asset name: " + name);
        }
        Path bucketDir = companyDir.resolve(bucket).normalize();
        if (!bucketDir.startsWith(companyDir)) {
            throw new ReportGenerationException("Bucket escapes company dir");
        }
        Path target = bucketDir.resolve(name).normalize();
        if (!target.startsWith(bucketDir)) {
            throw new ReportGenerationException("Asset name escapes bucket");
        }
        return target;
    }

    /** Browse all buckets — returns map {bucket → list-of-files-with-metadata}. */
    public Map<String, List<AssetInfo>> listAll(Path companyDir) {
        Map<String, List<AssetInfo>> out = new LinkedHashMap<>();
        for (String b : BUCKETS) out.put(b, list(companyDir, b));
        return out;
    }

    public List<AssetInfo> list(Path companyDir, String bucket) {
        requireValidBucket(bucket);
        Path dir = companyDir.resolve(bucket);
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> walk = Files.list(dir)) {
            List<AssetInfo> out = new ArrayList<>();
            walk.filter(Files::isRegularFile).forEach(p -> {
                try {
                    AssetInfo a = new AssetInfo();
                    a.name = p.getFileName().toString();
                    a.bucket = bucket;
                    a.size = Files.size(p);
                    a.modifiedAt = Files.getLastModifiedTime(p).toInstant().toString();
                    a.contentType = guessContentType(a.name);
                    out.add(a);
                } catch (IOException ignored) { }
            });
            out.sort(Comparator.comparing(a -> a.name));
            return out;
        } catch (IOException e) {
            throw new ReportGenerationException(
                    "Failed to list bucket " + bucket + ": " + e.getMessage(), e);
        }
    }

    public Path save(Path companyDir, String bucket, String name, InputStream content) {
        Path target = resolveSafe(companyDir, bucket, name);
        try {
            Files.createDirectories(target.getParent());
            Files.copy(content, target, StandardCopyOption.REPLACE_EXISTING);
            return target;
        } catch (IOException e) {
            throw new ReportGenerationException(
                    "Failed to save asset: " + e.getMessage(), e);
        }
    }

    public InputStream open(Path companyDir, String bucket, String name) {
        Path target = resolveSafe(companyDir, bucket, name);
        if (!Files.exists(target)) {
            throw new ReportGenerationException("Asset not found: " + bucket + "/" + name);
        }
        try { return Files.newInputStream(target); }
        catch (IOException e) {
            throw new ReportGenerationException(
                    "Failed to open asset: " + e.getMessage(), e);
        }
    }

    public long size(Path companyDir, String bucket, String name) {
        Path target = resolveSafe(companyDir, bucket, name);
        try { return Files.size(target); }
        catch (IOException e) { return -1; }
    }

    /** Soft-delete: move to {@code .meta/trash/<ts>-<bucket>-<name>}. */
    public Path softDelete(Path companyDir, String bucket, String name) {
        Path target = resolveSafe(companyDir, bucket, name);
        if (!Files.exists(target)) {
            throw new ReportGenerationException("Asset not found: " + bucket + "/" + name);
        }
        try {
            Path trashDir = companyDir.resolve(".meta").resolve("trash");
            Files.createDirectories(trashDir);
            String trashName = Instant.now().toEpochMilli() + "-" + bucket + "-" + name;
            Path dest = trashDir.resolve(trashName);
            Files.move(target, dest, StandardCopyOption.REPLACE_EXISTING);
            return dest;
        } catch (IOException e) {
            throw new ReportGenerationException(
                    "Failed to soft-delete: " + e.getMessage(), e);
        }
    }

    public List<TrashEntry> listTrash(Path companyDir) {
        Path trashDir = companyDir.resolve(".meta").resolve("trash");
        if (!Files.isDirectory(trashDir)) return List.of();
        try (Stream<Path> walk = Files.list(trashDir)) {
            List<TrashEntry> out = new ArrayList<>();
            walk.filter(Files::isRegularFile).forEach(p -> {
                try {
                    String fn = p.getFileName().toString();
                    int dash1 = fn.indexOf('-');
                    int dash2 = fn.indexOf('-', dash1 + 1);
                    if (dash1 < 0 || dash2 < 0) return;
                    long ts = Long.parseLong(fn.substring(0, dash1));
                    String bucket = fn.substring(dash1 + 1, dash2);
                    String name   = fn.substring(dash2 + 1);
                    TrashEntry t = new TrashEntry();
                    t.trashName = fn;
                    t.bucket = bucket;
                    t.originalName = name;
                    t.deletedAt = Instant.ofEpochMilli(ts).toString();
                    t.size = Files.size(p);
                    out.add(t);
                } catch (Exception ignored) { }
            });
            out.sort(Comparator.comparing((TrashEntry t) -> t.deletedAt).reversed());
            return out;
        } catch (IOException e) {
            return List.of();
        }
    }

    public Path restore(Path companyDir, String trashName) {
        Path trashDir = companyDir.resolve(".meta").resolve("trash");
        Path src = trashDir.resolve(trashName).normalize();
        if (!src.startsWith(trashDir) || !Files.exists(src)) {
            throw new ReportGenerationException("Trash entry not found: " + trashName);
        }
        int dash1 = trashName.indexOf('-');
        int dash2 = trashName.indexOf('-', dash1 + 1);
        if (dash1 < 0 || dash2 < 0) {
            throw new ReportGenerationException("Malformed trash entry: " + trashName);
        }
        String bucket = trashName.substring(dash1 + 1, dash2);
        String name   = trashName.substring(dash2 + 1);
        Path dest = resolveSafe(companyDir, bucket, name);
        try {
            Files.createDirectories(dest.getParent());
            Files.move(src, dest, StandardCopyOption.REPLACE_EXISTING);
            return dest;
        } catch (IOException e) {
            throw new ReportGenerationException(
                    "Failed to restore: " + e.getMessage(), e);
        }
    }

    /** Permanently delete trash entries older than the retention window. */
    public int sweepTrash(Path companyDir) {
        Path trashDir = companyDir.resolve(".meta").resolve("trash");
        if (!Files.isDirectory(trashDir)) return 0;
        Instant cutoff = Instant.now().minus(trashRetention);
        int deleted = 0;
        try (Stream<Path> walk = Files.list(trashDir)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                try {
                    String fn = p.getFileName().toString();
                    int dash1 = fn.indexOf('-');
                    if (dash1 < 0) continue;
                    long ts = Long.parseLong(fn.substring(0, dash1));
                    if (Instant.ofEpochMilli(ts).isBefore(cutoff)) {
                        Files.deleteIfExists(p);
                        deleted++;
                    }
                } catch (Exception ignored) { }
            }
        } catch (IOException e) {
            log.warn("trash sweep failed for {}: {}", companyDir, e.getMessage());
        }
        return deleted;
    }

    public static String guessContentType(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".png"))   return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".gif"))   return "image/gif";
        if (n.endsWith(".webp"))  return "image/webp";
        if (n.endsWith(".svg"))   return "image/svg+xml";
        if (n.endsWith(".pdf"))   return "application/pdf";
        if (n.endsWith(".json"))  return "application/json";
        if (n.endsWith(".xml") || n.endsWith(".jrxml")) return "application/xml";
        if (n.endsWith(".csv"))   return "text/csv";
        if (n.endsWith(".txt"))   return "text/plain";
        return "application/octet-stream";
    }

    public static class AssetInfo {
        public String name;
        public String bucket;
        public long   size;
        public String modifiedAt;
        public String contentType;
    }

    public static class TrashEntry {
        public String trashName;
        public String bucket;
        public String originalName;
        public String deletedAt;
        public long   size;
    }
}
