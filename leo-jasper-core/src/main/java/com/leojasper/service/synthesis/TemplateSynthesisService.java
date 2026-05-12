package com.leojasper.service.synthesis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.leojasper.service.ReportGenerationException;
import com.leojasper.service.template.TemplateRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates the full synthesis pipeline:
 * <pre>
 *   upload (PDF/image) + optional DTO + optional hint
 *      ↓
 *   AssetExtractor.renderFirstPage  → PNG sent to vision model
 *   AssetExtractor.extractEmbeddedImages → per-template assets
 *      ↓
 *   VisionAnalyzer.analyze          → LayoutModel
 *      ↓
 *   DtoFieldMatcher (optional)      → renamed fields/params
 *      ↓
 *   JrxmlGenerator                  → JRXML bytes
 *      ↓
 *   AssetStore + filesystem write   → registered with TemplateRegistry
 *      ↓
 *   SampleDataGenerator             → JSON sample records + parameters
 * </pre>
 */
public class TemplateSynthesisService {

    private static final Logger log = LoggerFactory.getLogger(TemplateSynthesisService.class);

    private final VisionAnalyzer analyzer;
    private final TemplateRegistry registry;
    private final Path templatesRoot;
    private final AssetStore assetStore;
    private final AssetExtractor extractor = new AssetExtractor();
    private final JrxmlGenerator jrxml = new JrxmlGenerator();
    private final SampleDataGenerator samples = new SampleDataGenerator();
    private final DtoFieldMatcher matcher = new DtoFieldMatcher();
    private final ObjectMapper jackson = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    public TemplateSynthesisService(VisionAnalyzer analyzer,
                                    TemplateRegistry registry,
                                    Path templatesRoot,
                                    AssetStore assetStore) {
        this.analyzer = analyzer;
        this.registry = registry;
        this.templatesRoot = templatesRoot.toAbsolutePath().normalize();
        this.assetStore = assetStore;
    }

    /**
     * Stage 1: just analyze. Returns the LayoutModel + any extracted assets so
     * the user can review/edit before committing to a template id.
     */
    public AnalysisResult analyze(byte[] uploadedBytes, String mimeType,
                                  String userHint, String dtoSource) {
        long t0 = System.nanoTime();
        AnalysisResult res = new AnalysisResult();
        res.analyzer = analyzer.name();

        byte[] previewPng = extractor.renderFirstPage(uploadedBytes, mimeType);
        res.previewBytes = previewPng;

        List<AssetExtractor.ExtractedAsset> embedded = extractor.extractEmbeddedImages(uploadedBytes);
        for (AssetExtractor.ExtractedAsset a : embedded) {
            AnalysisResult.AssetSummary s = new AnalysisResult.AssetSummary();
            s.name = a.name; s.contentType = a.contentType;
            s.width = a.width; s.height = a.height; s.size = a.bytes.length;
            res.embeddedAssets.add(s);
            res.embeddedBytes.put(a.name, a.bytes);
        }

        log.info("synthesizing layout — analyzer={}, hint='{}'", analyzer.name(), userHint);
        LayoutModel model = analyzer.analyze(previewPng, userHint);

        // Add the embedded assets to the model so the generator knows about them.
        for (AssetExtractor.ExtractedAsset a : embedded) {
            LayoutModel.AssetRef ref = new LayoutModel.AssetRef();
            ref.name = a.name; ref.contentType = a.contentType;
            ref.width = a.width; ref.height = a.height;
            ref.description = "Extracted from source PDF";
            model.assets.add(ref);
        }

        if (dtoSource != null && !dtoSource.isBlank()) {
            res.dtoMatch = matcher.apply(model, dtoSource);
        }

        res.layout = model;
        res.elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        return res;
    }

    /**
     * Stage 2: persist a (potentially user-edited) LayoutModel as a template.
     * Writes the JRXML into the template registry root, copies any assets into
     * the per-template asset store, and emits a manifest for traceability.
     */
    public SaveResult save(String templateId, LayoutModel model,
                           Map<String, byte[]> assetsToPersist) {
        if (templateId == null || templateId.isBlank()) {
            throw new ReportGenerationException("templateId is required");
        }
        if (templateId.contains("..") || templateId.contains("/") || templateId.contains("\\")) {
            throw new ReportGenerationException("Invalid templateId: " + templateId);
        }
        try {
            String xml = jrxml.generate(templateId, model);
            Path target = templatesRoot.resolve(templateId + ".jrxml").normalize();
            if (!target.startsWith(templatesRoot)) {
                throw new ReportGenerationException("templateId escapes templates root");
            }
            Files.createDirectories(target.getParent());
            try (var in = new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }

            // Persist assets keyed by name into the template's asset dir.
            if (assetsToPersist != null) {
                for (Map.Entry<String, byte[]> e : assetsToPersist.entrySet()) {
                    assetStore.save(templateId, e.getKey(), e.getValue());
                }
            }

            // Always save the model JSON too — useful for "regenerate" workflows.
            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("templateId", templateId);
            manifest.put("createdAt", Instant.now().toString());
            manifest.put("analyzer", analyzer.name());
            manifest.put("model", model);
            assetStore.save(templateId, "_manifest.json",
                    jackson.writeValueAsBytes(manifest));

            // Sample data, ready for testing.
            byte[] jrxmlBytes = xml.getBytes(StandardCharsets.UTF_8);
            String sampleJson = samples.generateRecords(jrxmlBytes, 5, model);
            String sampleParams = samples.generateParameters(jrxmlBytes, model);
            assetStore.save(templateId, "sample-data.json",
                    sampleJson.getBytes(StandardCharsets.UTF_8));
            assetStore.save(templateId, "sample-parameters.json",
                    sampleParams.getBytes(StandardCharsets.UTF_8));

            SaveResult r = new SaveResult();
            r.templateId    = templateId;
            r.jrxmlPath     = target.toString();
            r.jrxml         = xml;
            r.sampleData    = sampleJson;
            r.sampleParameters = sampleParams;
            r.assets        = assetStore.list(templateId);
            return r;
        } catch (ReportGenerationException e) {
            throw e;
        } catch (IOException e) {
            throw new ReportGenerationException(
                    "Failed to save template " + templateId + ": " + e.getMessage(), e);
        }
    }

    /** Generate sample data for an already-saved template — uses the manifest if available. */
    public String generateSampleData(String templateId, int rowCount) {
        try {
            byte[] jrxmlBytes;
            try (var in = registry.openJrxml(templateId)) {
                jrxmlBytes = in.readAllBytes();
            }
            LayoutModel hints = readManifest(templateId);
            return samples.generateRecords(jrxmlBytes, rowCount, hints);
        } catch (IOException e) {
            throw new ReportGenerationException(
                    "Failed to generate sample data: " + e.getMessage(), e);
        }
    }

    public String generateSampleParameters(String templateId) {
        try {
            byte[] jrxmlBytes;
            try (var in = registry.openJrxml(templateId)) {
                jrxmlBytes = in.readAllBytes();
            }
            LayoutModel hints = readManifest(templateId);
            return samples.generateParameters(jrxmlBytes, hints);
        } catch (IOException e) {
            throw new ReportGenerationException(
                    "Failed to generate sample parameters: " + e.getMessage(), e);
        }
    }

    public String generateSampleCsv(String templateId, int rowCount) {
        try {
            byte[] jrxmlBytes;
            try (var in = registry.openJrxml(templateId)) {
                jrxmlBytes = in.readAllBytes();
            }
            LayoutModel hints = readManifest(templateId);
            return samples.generateCsv(jrxmlBytes, rowCount, hints);
        } catch (IOException e) {
            throw new ReportGenerationException(
                    "Failed to generate sample CSV: " + e.getMessage(), e);
        }
    }

    public AssetStore assetStore() { return assetStore; }

    public VisionAnalyzer analyzer() { return analyzer; }

    private LayoutModel readManifest(String templateId) {
        try (var in = assetStore.open(templateId, "_manifest.json")) {
            Map<?, ?> root = jackson.readValue(in, Map.class);
            Object model = root.get("model");
            if (model == null) return null;
            return jackson.convertValue(model, LayoutModel.class);
        } catch (Exception e) {
            return null;
        }
    }

    // ===== response shapes =====

    public static class AnalysisResult {
        public String analyzer;
        public LayoutModel layout;
        public DtoFieldMatcher.DtoMatchResult dtoMatch;
        public List<AssetSummary> embeddedAssets = new java.util.ArrayList<>();
        public Map<String, byte[]> embeddedBytes = new LinkedHashMap<>();
        public byte[] previewBytes;       // PNG of the page (server keeps this internally)
        public long elapsedMs;

        public static class AssetSummary {
            public String name;
            public String contentType;
            public int width;
            public int height;
            public int size;
        }
    }

    public static class SaveResult {
        public String templateId;
        public String jrxmlPath;
        public String jrxml;
        public String sampleData;
        public String sampleParameters;
        public List<String> assets;
    }
}
