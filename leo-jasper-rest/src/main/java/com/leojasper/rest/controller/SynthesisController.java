package com.leojasper.rest.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leojasper.service.ReportGenerationException;
import com.leojasper.service.synthesis.LayoutModel;
import com.leojasper.service.synthesis.TemplateSynthesisService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/synthesis")
public class SynthesisController {

    private final TemplateSynthesisService service;
    private final ObjectMapper jackson = new ObjectMapper();

    public SynthesisController(TemplateSynthesisService service) {
        this.service = service;
    }

    /**
     * Stage 1 — analyze an uploaded PDF/image. Returns the LayoutModel,
     * extracted asset metadata, the rendered preview as base64 PNG, and the
     * embedded image bytes (also base64) so the user can review before saving.
     */
    @PostMapping(value = "/analyze", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> analyze(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "hint",      required = false) String hint,
            @RequestParam(value = "dtoSource", required = false) String dtoSource) throws IOException {

        if (file == null || file.isEmpty()) {
            throw new ReportGenerationException("file is required");
        }
        TemplateSynthesisService.AnalysisResult res =
                service.analyze(file.getBytes(), file.getContentType(), hint, dtoSource);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("analyzer", res.analyzer);
        body.put("elapsedMs", res.elapsedMs);
        body.put("layout", res.layout);
        body.put("dtoMatch", res.dtoMatch);
        body.put("embeddedAssets", res.embeddedAssets);
        body.put("preview", "data:image/png;base64," +
                Base64.getEncoder().encodeToString(res.previewBytes));

        // Inline embedded asset bytes too — the UI can display them and the
        // client picks which ones to persist when calling /save.
        Map<String, String> assetBytes = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> e : res.embeddedBytes.entrySet()) {
            assetBytes.put(e.getKey(),
                    "data:image/png;base64," + Base64.getEncoder().encodeToString(e.getValue()));
        }
        body.put("embeddedAssetBytes", assetBytes);
        return body;
    }

    /**
     * Stage 2 — persist the LayoutModel as a JRXML template. Body is JSON:
     * <pre>
     * {
     *   "templateId": "invoice-2",
     *   "layout":     { ... LayoutModel ... },
     *   "assets":     { "logo.png": "&lt;base64&gt;" }
     * }
     * </pre>
     */
    @PostMapping(value = "/save", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> save(@RequestBody Map<String, Object> body) {
        String templateId = (String) body.get("templateId");
        if (templateId == null || templateId.isBlank()) {
            throw new ReportGenerationException("templateId is required");
        }

        LayoutModel layout = jackson.convertValue(body.get("layout"), LayoutModel.class);
        if (layout == null) {
            throw new ReportGenerationException("layout is required");
        }

        Map<String, byte[]> assets = new LinkedHashMap<>();
        Object assetsRaw = body.get("assets");
        if (assetsRaw instanceof Map<?, ?>) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) assetsRaw).entrySet()) {
                String name = String.valueOf(e.getKey());
                String value = String.valueOf(e.getValue());
                int comma = value.indexOf(",");
                String b64 = (value.startsWith("data:") && comma > 0)
                        ? value.substring(comma + 1) : value;
                assets.put(name, Base64.getDecoder().decode(b64));
            }
        }

        TemplateSynthesisService.SaveResult r = service.save(templateId, layout, assets);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("templateId",       r.templateId);
        out.put("jrxmlPath",        r.jrxmlPath);
        out.put("jrxml",            r.jrxml);
        out.put("sampleData",       r.sampleData);
        out.put("sampleParameters", r.sampleParameters);
        out.put("assets",           r.assets);
        return out;
    }

    /** Re-emit sample data for an existing template. */
    @GetMapping("/{templateId}/sample-data")
    public ResponseEntity<String> sampleData(@PathVariable String templateId,
                                             @RequestParam(defaultValue = "5") int rows,
                                             @RequestParam(defaultValue = "json") String fmt) {
        String body = switch (fmt.toLowerCase()) {
            case "csv"  -> service.generateSampleCsv(templateId, rows);
            case "json" -> service.generateSampleData(templateId, rows);
            default     -> throw new ReportGenerationException("Unsupported sample format: " + fmt);
        };
        HttpHeaders h = new HttpHeaders();
        h.add(HttpHeaders.CONTENT_TYPE,
                "csv".equalsIgnoreCase(fmt) ? "text/csv;charset=UTF-8" : "application/json");
        return new ResponseEntity<>(body, h, 200);
    }

    @GetMapping("/{templateId}/sample-parameters")
    public ResponseEntity<String> sampleParameters(@PathVariable String templateId) {
        String body = service.generateSampleParameters(templateId);
        HttpHeaders h = new HttpHeaders();
        h.add(HttpHeaders.CONTENT_TYPE, "application/json");
        return new ResponseEntity<>(body, h, 200);
    }

    /** List a template's assets (logos, headers, manifests). */
    @GetMapping("/{templateId}/assets")
    public List<String> listAssets(@PathVariable String templateId) {
        return service.assetStore().list(templateId);
    }

    /** Download one specific asset. */
    @GetMapping("/{templateId}/assets/{name:.+}")
    public ResponseEntity<byte[]> downloadAsset(@PathVariable String templateId,
                                                @PathVariable String name) throws IOException {
        try (InputStream in = service.assetStore().open(templateId, name)) {
            byte[] bytes = in.readAllBytes();
            HttpHeaders h = new HttpHeaders();
            h.add(HttpHeaders.CONTENT_TYPE,    guessContentType(name));
            h.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"");
            return new ResponseEntity<>(bytes, h, 200);
        }
    }

    @GetMapping("/info")
    public Map<String, Object> info() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("analyzer", service.analyzer().name());
        return m;
    }

    private String guessContentType(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".png"))  return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".json")) return "application/json";
        if (n.endsWith(".jrxml") || n.endsWith(".xml")) return "application/xml";
        return "application/octet-stream";
    }
}
