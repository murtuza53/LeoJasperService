package com.leojasper.rest.controller;

import com.leojasper.service.ReportGenerationException;
import com.leojasper.service.security.AssetService;
import com.leojasper.service.security.LicenseContext;
import org.springframework.http.HttpHeaders;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * License-keyed asset CRUD. All routes require a valid {@code X-Key-Id} +
 * {@code X-Key-Secret} (gated by the {@link com.leojasper.rest.security.LicenseFilter}).
 *
 * <p>Buckets are fixed: {@code images}, {@code templates}, {@code data},
 * {@code other}.
 */
@RestController
@RequestMapping("/api/assets")
public class AssetsController {

    private final AssetService assets;

    public AssetsController(AssetService assets) { this.assets = assets; }

    private LicenseContext ctx() {
        LicenseContext c = LicenseContext.get();
        if (c == null) throw new ReportGenerationException("No license context — server bug or filter misconfig");
        return c;
    }

    @GetMapping
    public Map<String, Object> listAll() {
        LicenseContext c = ctx();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("companyName", c.companyName);
        out.put("scope", c.scope.name());
        out.put("buckets", AssetService.BUCKETS);
        out.put("contents", assets.listAll(c.companyDir));
        return out;
    }

    @GetMapping("/{bucket}")
    public List<AssetService.AssetInfo> list(@PathVariable String bucket) {
        return assets.list(ctx().companyDir, bucket);
    }

    @PostMapping("/{bucket}/{name:.+}")
    public Map<String, Object> upload(@PathVariable String bucket,
                                      @PathVariable String name,
                                      @RequestParam("file") MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new ReportGenerationException("file is required");
        }
        try (InputStream in = file.getInputStream()) {
            java.nio.file.Path saved = assets.save(ctx().companyDir, bucket, name, in);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("path", saved.toString());
            out.put("size", file.getSize());
            out.put("contentType", AssetService.guessContentType(name));
            return out;
        }
    }

    @GetMapping("/{bucket}/{name:.+}")
    public ResponseEntity<byte[]> download(@PathVariable String bucket,
                                           @PathVariable String name,
                                           @RequestParam(defaultValue = "false") boolean inline) throws IOException {
        try (InputStream in = assets.open(ctx().companyDir, bucket, name)) {
            byte[] bytes = in.readAllBytes();
            HttpHeaders h = new HttpHeaders();
            h.add(HttpHeaders.CONTENT_TYPE, AssetService.guessContentType(name));
            h.add(HttpHeaders.CONTENT_DISPOSITION,
                    (inline ? "inline" : "attachment") + "; filename=\"" + name + "\"");
            h.add("Cache-Control", "private, max-age=60");
            return new ResponseEntity<>(bytes, h, 200);
        }
    }

    @DeleteMapping("/{bucket}/{name:.+}")
    public Map<String, Object> delete(@PathVariable String bucket, @PathVariable String name) {
        java.nio.file.Path moved = assets.softDelete(ctx().companyDir, bucket, name);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("status", "soft-deleted");
        out.put("trashPath", moved.toString());
        out.put("retentionDays", assets.trashRetention().toDays());
        return out;
    }

    /** A client can also see/restore its own trash without admin help. */
    @GetMapping("/_trash")
    public List<AssetService.TrashEntry> trash() {
        return assets.listTrash(ctx().companyDir);
    }

    @PostMapping("/_trash/{trashName}/restore")
    public Map<String, Object> restore(@PathVariable String trashName) {
        java.nio.file.Path restored = assets.restore(ctx().companyDir, trashName);
        return Map.of("ok", true, "restored", restored.toString());
    }
}
