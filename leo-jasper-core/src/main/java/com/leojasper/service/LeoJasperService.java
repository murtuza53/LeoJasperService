package com.leojasper.service;

import com.leojasper.service.datasource.JRDataSourceFactory;
import com.leojasper.service.pdf.PdfPostProcessor;
import com.leojasper.service.template.RawPathTemplateRegistry;
import com.leojasper.service.template.TemplateRegistry;
import io.micrometer.core.instrument.Timer;
import net.sf.jasperreports.engine.JRDataSource;
import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JRParameter;
import net.sf.jasperreports.engine.JasperCompileManager;
import net.sf.jasperreports.engine.JasperExportManager;
import net.sf.jasperreports.engine.JasperFillManager;
import net.sf.jasperreports.engine.JasperPrint;
import net.sf.jasperreports.engine.JasperReport;
import net.sf.jasperreports.engine.export.HtmlExporter;
import net.sf.jasperreports.engine.export.JRCsvExporter;
import net.sf.jasperreports.engine.export.JRGraphics2DExporter;
import net.sf.jasperreports.engine.export.JRRtfExporter;
import net.sf.jasperreports.engine.export.JRTextExporter;
import net.sf.jasperreports.engine.export.JRXlsExporter;
import net.sf.jasperreports.engine.export.oasis.JROdtExporter;
import net.sf.jasperreports.engine.export.ooxml.JRDocxExporter;
import net.sf.jasperreports.engine.export.ooxml.JRPptxExporter;
import net.sf.jasperreports.engine.export.ooxml.JRXlsxExporter;
import net.sf.jasperreports.engine.fill.JRSwapFileVirtualizer;
import net.sf.jasperreports.engine.util.JRSwapFile;
import net.sf.jasperreports.export.SimpleExporterInput;
import net.sf.jasperreports.export.SimpleGraphics2DExporterOutput;
import net.sf.jasperreports.export.SimpleGraphics2DReportConfiguration;
import net.sf.jasperreports.export.SimpleHtmlExporterOutput;
import net.sf.jasperreports.export.SimpleOdtReportConfiguration;
import net.sf.jasperreports.export.SimpleOutputStreamExporterOutput;
import net.sf.jasperreports.export.SimplePptxReportConfiguration;
import net.sf.jasperreports.export.SimpleTextReportConfiguration;
import net.sf.jasperreports.export.SimpleWriterExporterOutput;
import net.sf.jasperreports.export.SimpleXlsReportConfiguration;
import net.sf.jasperreports.export.SimpleXlsxReportConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.ResourceBundle;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Compiles a JRXML template, fills it from a heterogeneous data source, and
 * exports it to one of {@link OutputFormat}'s 12 supported formats.
 *
 * <p>The service is thread-safe. Compiled templates are cached, keyed by the
 * registry's stable cache key so file edits transparently invalidate.
 *
 * <p>Configure with fluent setters: {@code new LeoJasperService()
 * .templateRegistry(...).useSqlVirtualizer(true).pdfPostProcessors(...)}.
 */
public class LeoJasperService {

    private static final Logger log = LoggerFactory.getLogger(LeoJasperService.class);

    private final Map<String, JasperReport> compiledCache = new ConcurrentHashMap<>();
    private final Map<String, JRDataSourceFactory> factories = new ConcurrentHashMap<>();

    private TemplateRegistry templateRegistry = new RawPathTemplateRegistry();
    private boolean cacheEnabled = true;
    private boolean useSqlVirtualizer = false;
    private List<PdfPostProcessor> pdfPostProcessors = List.of();
    private ReportMetrics metrics = new ReportMetrics();

    public LeoJasperService() {
        ServiceLoader.load(JRDataSourceFactory.class).forEach(this::registerDataSourceFactory);
        // A URL fetch that 404s or times out should NOT fail the whole report.
        // Switch JR's default image-error behaviour from "throw" to "render the
        // broken-image icon". File-not-found within company assets is handled
        // by CompanyAssetFileResolver returning the placeholder PNG instead.
        net.sf.jasperreports.engine.DefaultJasperReportsContext.getInstance()
                .setProperty("net.sf.jasperreports.image.on.error.type", "Icon");
    }

    // ----------------------------------------------------------------------
    // Configuration (fluent)
    // ----------------------------------------------------------------------

    public LeoJasperService templateRegistry(TemplateRegistry registry) {
        this.templateRegistry = Objects.requireNonNullElseGet(registry, RawPathTemplateRegistry::new);
        return this;
    }

    public LeoJasperService cacheEnabled(boolean enabled) {
        this.cacheEnabled = enabled;
        if (!enabled) compiledCache.clear();
        return this;
    }

    public LeoJasperService useSqlVirtualizer(boolean enabled) {
        this.useSqlVirtualizer = enabled;
        return this;
    }

    public LeoJasperService pdfPostProcessors(List<PdfPostProcessor> processors) {
        this.pdfPostProcessors = processors == null ? List.of() : List.copyOf(processors);
        return this;
    }

    public LeoJasperService metrics(ReportMetrics metrics) {
        this.metrics = metrics == null ? new ReportMetrics() : metrics;
        return this;
    }

    public LeoJasperService registerDataSourceFactory(JRDataSourceFactory factory) {
        factories.put(factory.name().toLowerCase(Locale.ROOT), factory);
        return this;
    }

    public void clearCompiledCache() { compiledCache.clear(); }

    public TemplateRegistry getTemplateRegistry() { return templateRegistry; }

    public ReportMetrics getMetrics() { return metrics; }

    // ----------------------------------------------------------------------
    // Public API — single page / single format
    // ----------------------------------------------------------------------

    public byte[] generateReport(String jrxmlPath, Object inputData,
                                 String inputFormat, String outputFormat) {
        return generateReport(jrxmlPath, inputData, inputFormat, outputFormat, Collections.emptyMap());
    }

    public byte[] generateReport(String jrxmlPath, Object inputData,
                                 String inputFormat, String outputFormat,
                                 Map<String, Object> reportParameters) {
        Objects.requireNonNull(jrxmlPath, "jrxmlPath is required");
        Objects.requireNonNull(outputFormat, "outputFormat is required");

        OutputFormat fmt = OutputFormat.from(outputFormat);
        ByteArrayOutputStream baos = new ByteArrayOutputStream(64 * 1024);
        generateReport(jrxmlPath, inputData, inputFormat, fmt, reportParameters, baos);

        if (fmt == OutputFormat.PDF && !pdfPostProcessors.isEmpty()) {
            byte[] pdf = baos.toByteArray();
            for (PdfPostProcessor pp : pdfPostProcessors) {
                pdf = pp.process(pdf);
            }
            return pdf;
        }
        return baos.toByteArray();
    }

    public void generateReport(String jrxmlPath, Object inputData,
                               String inputFormat, String outputFormat,
                               Map<String, Object> reportParameters,
                               OutputStream out) {
        Objects.requireNonNull(out, "OutputStream is required");
        OutputFormat fmt = OutputFormat.from(outputFormat);
        if (fmt == OutputFormat.PDF && !pdfPostProcessors.isEmpty()) {
            // post-processors operate on bytes, so buffer first
            ByteArrayOutputStream baos = new ByteArrayOutputStream(64 * 1024);
            generateReport(jrxmlPath, inputData, inputFormat, fmt, reportParameters, baos);
            byte[] pdf = baos.toByteArray();
            for (PdfPostProcessor pp : pdfPostProcessors) {
                pdf = pp.process(pdf);
            }
            try { out.write(pdf); } catch (IOException e) {
                throw new ReportGenerationException("Writing post-processed PDF failed", e);
            }
            return;
        }
        generateReport(jrxmlPath, inputData, inputFormat, fmt, reportParameters, out);
    }

    private void generateReport(String jrxmlPath, Object inputData,
                                String inputFormat, OutputFormat fmt,
                                Map<String, Object> reportParameters,
                                OutputStream out) {
        try {
            JasperReport jasperReport = compile(jrxmlPath);
            JasperPrint jasperPrint = fill(jasperReport, inputData, inputFormat, reportParameters);
            export(jasperPrint, fmt, out);
        } catch (ReportGenerationException e) {
            metrics.incrementError(e.getMessage());
            throw e;
        } catch (Exception e) {
            metrics.incrementError("generation");
            throw new ReportGenerationException(
                    "Failed to generate report from " + jrxmlPath + ": " + e.getMessage(), e);
        }
    }

    // ----------------------------------------------------------------------
    // Public API — multi-page PNG (suggestion 1)
    // ----------------------------------------------------------------------

    /**
     * Generate a report from raw JRXML bytes — bypasses the template registry
     * entirely. Useful when a client uploads a one-off template that should not
     * be persisted into the registry.
     */
    public byte[] generateReportFromJrxml(byte[] jrxmlBytes, Object inputData,
                                          String inputFormat, String outputFormat,
                                          Map<String, Object> reportParameters) {
        Objects.requireNonNull(jrxmlBytes, "jrxmlBytes is required");
        Objects.requireNonNull(outputFormat, "outputFormat is required");
        OutputFormat fmt = OutputFormat.from(outputFormat);
        try {
            JasperReport jr;
            try (InputStream in = new java.io.ByteArrayInputStream(jrxmlBytes)) {
                jr = JasperCompileManager.compileReport(in);
            }
            JasperPrint jp = fill(jr, inputData, inputFormat, reportParameters);
            ByteArrayOutputStream baos = new ByteArrayOutputStream(64 * 1024);
            export(jp, fmt, baos);
            if (fmt == OutputFormat.PDF && !pdfPostProcessors.isEmpty()) {
                byte[] pdf = baos.toByteArray();
                for (PdfPostProcessor pp : pdfPostProcessors) pdf = pp.process(pdf);
                return pdf;
            }
            return baos.toByteArray();
        } catch (ReportGenerationException e) {
            metrics.incrementError(e.getMessage());
            throw e;
        } catch (Exception e) {
            metrics.incrementError("generation");
            throw new ReportGenerationException(
                    "Failed to generate report from inline JRXML: " + e.getMessage(), e);
        }
    }

    /** Render every page to a PNG and return them as a list, in page order. */
    public List<byte[]> generateReportImages(String jrxmlPath, Object inputData,
                                             String inputFormat,
                                             Map<String, Object> reportParameters,
                                             float zoom) {
        try {
            JasperReport jr = compile(jrxmlPath);
            JasperPrint jp = fill(jr, inputData, inputFormat, reportParameters);
            int pages = jp.getPages() == null ? 0 : jp.getPages().size();
            List<byte[]> result = new ArrayList<>(pages);
            for (int i = 0; i < pages; i++) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream(32 * 1024);
                renderPageAsPng(jp, i, zoom, baos);
                result.add(baos.toByteArray());
            }
            return result;
        } catch (ReportGenerationException e) {
            metrics.incrementError(e.getMessage());
            throw e;
        } catch (Exception e) {
            metrics.incrementError("images");
            throw new ReportGenerationException(
                    "Failed to render report images: " + e.getMessage(), e);
        }
    }

    /** Convenience: locale + resource bundle merged into the parameter map. */
    public static Map<String, Object> withLocale(Map<String, Object> params,
                                                 Locale locale, ResourceBundle bundle) {
        Map<String, Object> p = new HashMap<>(params == null ? Collections.emptyMap() : params);
        if (locale != null) p.put(JRParameter.REPORT_LOCALE, locale);
        if (bundle != null) p.put(JRParameter.REPORT_RESOURCE_BUNDLE, bundle);
        return p;
    }

    // ----------------------------------------------------------------------
    // Compile
    // ----------------------------------------------------------------------

    private JasperReport compile(String templateId) throws JRException, IOException {
        Timer.Sample sample = metrics.startTimer();
        try {
            String key = templateRegistry.cacheKey(templateId);
            if (cacheEnabled) {
                JasperReport cached = compiledCache.get(key);
                if (cached != null) return cached;
            }
            JasperReport compiled;
            try (InputStream in = templateRegistry.openJrxml(templateId)) {
                compiled = JasperCompileManager.compileReport(in);
            }
            if (cacheEnabled) compiledCache.put(key, compiled);
            return compiled;
        } finally {
            metrics.recordCompile(sample);
        }
    }

    // ----------------------------------------------------------------------
    // Fill
    // ----------------------------------------------------------------------

    private JasperPrint fill(JasperReport jasperReport, Object inputData,
                             String inputFormatHint, Map<String, Object> reportParameters)
            throws JRException, IOException {

        Timer.Sample sample = metrics.startTimer();
        Map<String, Object> params = new HashMap<>(
                reportParameters == null ? Collections.emptyMap() : reportParameters);

        InputFormat format = resolveFormat(inputData, inputFormatHint);

        // json-doc — single file with both 'parameters' and 'data' sections.
        // Pull the parameters into the JR parameter map, then continue with
        // the bytes of the 'data' array as if the caller had sent plain json.
        if (format == InputFormat.JSON_DOC) {
            JsonDocSplit split = splitJsonDoc(inputData);
            // File parameters are the base; caller-supplied params win so the
            // test page / API caller can override per-request.
            Map<String, Object> merged = new HashMap<>(split.parameters);
            merged.putAll(params);
            params = merged;
            inputData       = split.dataBytes;
            inputFormatHint = "json";
            format          = InputFormat.JSON;
            log.info("json-doc split: {} parameter(s) from file, {} bytes of data; sample keys={}",
                     split.parameters.size(), split.dataBytes.length,
                     split.parameters.keySet().stream().limit(3).toList());
        }

        JRSwapFileVirtualizer virtualizer = null;
        if (useSqlVirtualizer && format == InputFormat.SQL) {
            virtualizer = createVirtualizer();
            params.put(JRParameter.REPORT_VIRTUALIZER, virtualizer);
        }

        // Coerce supplied parameter values to the types declared in the JRXML
        // (Number→Number conversions only; mismatches throw a clear 400 here
        // instead of a confusing JR cast at evaluation time).
        coerceParamTypes(jasperReport, params);

        // Build a per-fill JasperReportsContext that resolves bare image
        // filenames against the calling company's asset folder, with a
        // placeholder PNG fallback for misses + URL failures.
        net.sf.jasperreports.engine.JasperReportsContext jrCtx = perFillContext();

        try {
            JasperFillManager fm = JasperFillManager.getInstance(jrCtx);
            // SQL with a live JDBC Connection lets the report's <queryString/> run.
            if (format == InputFormat.SQL && inputData instanceof Connection) {
                JasperPrint jp = fm.fill(jasperReport, params, (Connection) inputData);
                metrics.recordFill(sample, format.name().toLowerCase(Locale.ROOT));
                return jp;
            }

            JRDataSource ds = buildDataSource(inputData, format, inputFormatHint);
            JasperPrint jp = fm.fill(jasperReport, params, ds);
            metrics.recordFill(sample, format.name().toLowerCase(Locale.ROOT));
            return jp;
        } finally {
            if (virtualizer != null) virtualizer.cleanup();
        }
    }

    /**
     * Builds a {@code SimpleJasperReportsContext} for one fill, with the
     * company-aware {@link com.leojasper.service.image.CompanyAssetRepositoryService}
     * registered ahead of JR's defaults so image lookups try the company's
     * asset folder first, then fall through to default behaviour for anything
     * non-image (subreports, properties files, etc).
     *
     * <p>If there's no {@link com.leojasper.service.security.LicenseContext}
     * (CLI run, tests), returns the default context unchanged — JR's standard
     * image resolution applies.
     */
    private net.sf.jasperreports.engine.JasperReportsContext perFillContext() {
        com.leojasper.service.security.LicenseContext lc =
                com.leojasper.service.security.LicenseContext.get();
        if (lc == null || lc.companyDir == null) {
            return net.sf.jasperreports.engine.DefaultJasperReportsContext.getInstance();
        }
        net.sf.jasperreports.engine.SimpleJasperReportsContext ctx =
                new net.sf.jasperreports.engine.SimpleJasperReportsContext(
                        net.sf.jasperreports.engine.DefaultJasperReportsContext.getInstance());
        java.util.List<net.sf.jasperreports.repo.RepositoryService> services = new java.util.ArrayList<>();
        services.add(new com.leojasper.service.image.CompanyAssetRepositoryService(lc.companyDir));
        // Keep default services after ours so subreports / properties / fonts
        // continue to resolve through the engine's built-in behaviour.
        services.addAll(net.sf.jasperreports.engine.DefaultJasperReportsContext.getInstance()
                .getExtensions(net.sf.jasperreports.repo.RepositoryService.class));
        ctx.setExtensions(net.sf.jasperreports.repo.RepositoryService.class, services);
        return ctx;
    }

    /**
     * Walk the JRXML's declared parameters and coerce supplied values to the
     * declared Java type. The goal is "permissive on numerics, strict on
     * semantic mismatch":
     *
     * <ul>
     *   <li>{@code Number → Number}: silently converts (Integer 0 → Double 0.0,
     *       BigDecimal → Double, etc.). Survives JSON producing values that
     *       don't exactly match the JRXML's class declaration.</li>
     *   <li>{@code Anything → String}: {@code String.valueOf(v)}.</li>
     *   <li>{@code Boolean: String "true"/"false" → Boolean}.</li>
     *   <li>Everything else (including {@code String → Number}): rejected with
     *       a precise message naming the parameter, declared type, and actual
     *       type. Caller fixes the data — we don't paper over a type mismatch.</li>
     * </ul>
     */
    /**
     * Adds a {@code REPORT_FILE_RESOLVER} to the parameter map so JR resolves
     * bare image filenames against the calling company's asset folder.
     * No-op when there's no {@link com.leojasper.service.security.LicenseContext}
     * (e.g. CLI runs without the license filter installed) — JR then uses its
     * default resolver and any miss surfaces as the broken-image icon thanks
     * to the {@code on.error.type=Icon} property we set globally.
     */
    private void coerceParamTypes(JasperReport jr, Map<String, Object> params) {
        if (jr == null || params.isEmpty()) return;
        net.sf.jasperreports.engine.JRParameter[] declared = jr.getParameters();
        if (declared == null) return;

        for (net.sf.jasperreports.engine.JRParameter p : declared) {
            if (p.isSystemDefined()) continue;
            String name = p.getName();
            if (!params.containsKey(name)) continue;
            Object v = params.get(name);
            if (v == null) continue;

            Class<?> declaredType = p.getValueClass();
            if (declaredType == null || declaredType.isInstance(v)) continue;

            Object coerced = coerceOne(name, v, declaredType);
            params.put(name, coerced);
        }
    }

    private Object coerceOne(String name, Object v, Class<?> target) {
        // Number → Number
        if (v instanceof Number) {
            Number n = (Number) v;
            if (target == Double.class)     return n.doubleValue();
            if (target == Float.class)      return n.floatValue();
            if (target == Long.class)       return n.longValue();
            if (target == Integer.class)    return n.intValue();
            if (target == Short.class)      return n.shortValue();
            if (target == Byte.class)       return n.byteValue();
            if (target == java.math.BigDecimal.class) {
                return (v instanceof java.math.BigDecimal)
                        ? v : new java.math.BigDecimal(n.toString());
            }
            if (target == java.math.BigInteger.class) {
                return java.math.BigInteger.valueOf(n.longValue());
            }
            if (target == String.class)     return String.valueOf(v);
            // Number → some non-number type the JRXML asked for. Reject.
            throw typeMismatch(name, v, target);
        }
        // Anything → String
        if (target == String.class) {
            return String.valueOf(v);
        }
        // String "true"/"false" → Boolean
        if (v instanceof String && target == Boolean.class) {
            String s = ((String) v).trim().toLowerCase(Locale.ROOT);
            if ("true".equals(s) || "false".equals(s)) return Boolean.parseBoolean(s);
            throw typeMismatch(name, v, target);
        }
        // String → Number is intentionally rejected — that's a data-model issue
        // the caller should fix, not something we silently paper over.
        throw typeMismatch(name, v, target);
    }

    private ReportGenerationException typeMismatch(String paramName, Object v, Class<?> target) {
        String preview = String.valueOf(v);
        if (preview.length() > 60) preview = preview.substring(0, 60) + "…";
        return new ReportGenerationException(
                "Parameter '" + paramName + "' is declared as " + target.getName() +
                " but received " + v.getClass().getName() + " (\"" + preview + "\"). " +
                "Only Number→Number and Anything→String coercions are applied automatically; " +
                "fix the source data to send the correct type.");
    }

    /**
     * Pre-parser for the {@link InputFormat#JSON_DOC} payload. Splits the
     * top-level {@code parameters} object into a flat Java map and returns the
     * bytes of the {@code data} array unchanged, ready to be fed through the
     * regular JSON data-source factory.
     */
    private JsonDocSplit splitJsonDoc(Object inputData) throws IOException {
        com.fasterxml.jackson.databind.ObjectMapper jackson =
                new com.fasterxml.jackson.databind.ObjectMapper();
        byte[] bytes;
        try (InputStream in =
                     com.leojasper.service.datasource.InputDataReader.toInputStream(inputData)) {
            bytes = in.readAllBytes();
        }
        com.fasterxml.jackson.databind.JsonNode root;
        try {
            root = jackson.readTree(bytes);
        } catch (Exception e) {
            throw new ReportGenerationException(
                    "json-doc input is not valid JSON: " + e.getMessage(), e);
        }
        if (root == null || !root.isObject()) {
            throw new ReportGenerationException(
                    "json-doc input must be a JSON object with 'parameters' and 'data' keys");
        }

        Map<String, Object> params = new HashMap<>();
        com.fasterxml.jackson.databind.JsonNode pNode = root.get("parameters");
        if (pNode != null && pNode.isObject()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> p = jackson.convertValue(pNode, Map.class);
            if (p != null) {
                normalizeNumbers(p);   // BigDecimal/BigInteger → Double/Long
                params.putAll(p);
            }
        }

        com.fasterxml.jackson.databind.JsonNode dataNode = root.get("data");
        if (dataNode == null || dataNode.isNull()) {
            // Header-only document: emit an empty array so detail bands render zero rows.
            dataNode = jackson.createArrayNode();
        }
        if (!dataNode.isArray()) {
            throw new ReportGenerationException(
                    "json-doc: top-level 'data' must be a JSON array (got " +
                            dataNode.getNodeType() + ")");
        }

        byte[] dataBytes = jackson.writeValueAsBytes(dataNode);
        return new JsonDocSplit(params, dataBytes);
    }

    private static final class JsonDocSplit {
        final Map<String, Object> parameters;
        final byte[] dataBytes;
        JsonDocSplit(Map<String, Object> parameters, byte[] dataBytes) {
            this.parameters = parameters;
            this.dataBytes = dataBytes;
        }
    }

    /**
     * Coerce numeric values to JR-friendly types. Jackson can produce
     * {@link java.math.BigDecimal} for high-precision floating-point literals
     * (e.g. {@code 0.0000000000000000000000000000}); when the JRXML declares
     * the parameter as {@code java.lang.Double}, JR's runtime cast to Double
     * fails. This pass downgrades:
     * <ul>
     *   <li>{@code BigDecimal} → {@code Double}</li>
     *   <li>{@code BigInteger} → {@code Long}</li>
     * </ul>
     * applied recursively so nested objects are also covered. Other types
     * (Integer, Long, Double, Float, String, Boolean, null, List) pass through
     * unchanged.
     */
    @SuppressWarnings("unchecked")
    private static void normalizeNumbers(Map<String, Object> m) {
        for (Map.Entry<String, Object> e : m.entrySet()) {
            Object v = e.getValue();
            if (v instanceof java.math.BigDecimal) {
                e.setValue(((java.math.BigDecimal) v).doubleValue());
            } else if (v instanceof java.math.BigInteger) {
                e.setValue(((java.math.BigInteger) v).longValue());
            } else if (v instanceof Map<?, ?>) {
                normalizeNumbers((Map<String, Object>) v);
            } else if (v instanceof java.util.List<?>) {
                for (Object item : (java.util.List<Object>) v) {
                    if (item instanceof Map<?, ?>) {
                        normalizeNumbers((Map<String, Object>) item);
                    }
                }
            }
        }
    }

    private JRSwapFileVirtualizer createVirtualizer() {
        JRSwapFile swap = new JRSwapFile(System.getProperty("java.io.tmpdir"), 4096, 100);
        return new JRSwapFileVirtualizer(2, swap, true);
    }

    private InputFormat resolveFormat(Object inputData, String hint) {
        if (hint != null && !hint.isBlank()) {
            return InputFormat.from(hint);
        }
        if (inputData instanceof Connection || inputData instanceof java.sql.ResultSet) return InputFormat.SQL;
        if (inputData instanceof JRDataSource) return InputFormat.RAW;
        if (inputData instanceof java.util.Collection<?>) return InputFormat.BEANS;
        throw new ReportGenerationException(
                "Cannot auto-detect input format; pass an explicit inputFormat. Got: " +
                        (inputData == null ? "null" : inputData.getClass().getName()));
    }

    private JRDataSource buildDataSource(Object inputData, InputFormat format, String hint)
            throws JRException, IOException {

        // Prefer the named factory (matches `inputFormat` hint) — supports custom SPI plugins.
        if (hint != null && !hint.isBlank()) {
            JRDataSourceFactory custom = factories.get(hint.trim().toLowerCase(Locale.ROOT));
            if (custom != null) return custom.create(inputData);
        }
        // Fall back to the canonical factory for the resolved enum format.
        JRDataSourceFactory f = factories.get(format.name().toLowerCase(Locale.ROOT));
        if (f == null) {
            throw new ReportGenerationException(
                    "No data source factory registered for format " + format);
        }
        return f.create(inputData);
    }

    // ----------------------------------------------------------------------
    // Export
    // ----------------------------------------------------------------------

    private void export(JasperPrint jp, OutputFormat fmt, OutputStream out)
            throws JRException, IOException {

        Timer.Sample sample = metrics.startTimer();
        try {
            switch (fmt) {
                case PDF:
                    JasperExportManager.exportReportToPdfStream(jp, out);
                    return;

                case XLSX:    exportXlsx(jp, out); return;
                case XLS:     exportXls(jp, out); return;
                case HTML:    exportHtml(jp, out); return;
                case PNG:     renderPageAsPng(jp, 0, 1.0f, out); return;
                case PNG_ZIP: exportPngZip(jp, out); return;
                case DOCX:    exportDocx(jp, out); return;
                case ODT:     exportOdt(jp, out); return;
                case PPTX:    exportPptx(jp, out); return;
                case RTF:     exportRtf(jp, out); return;
                case CSV:     exportCsv(jp, out); return;
                case TEXT:    exportText(jp, out); return;

                default:
                    throw new ReportGenerationException("Unsupported output format: " + fmt);
            }
        } finally {
            metrics.recordExport(sample, fmt.name().toLowerCase(Locale.ROOT));
        }
    }

    private void exportXlsx(JasperPrint jp, OutputStream out) throws JRException {
        JRXlsxExporter ex = new JRXlsxExporter();
        ex.setExporterInput(new SimpleExporterInput(jp));
        ex.setExporterOutput(new SimpleOutputStreamExporterOutput(out));
        SimpleXlsxReportConfiguration cfg = new SimpleXlsxReportConfiguration();
        cfg.setOnePagePerSheet(false);
        cfg.setDetectCellType(true);
        cfg.setRemoveEmptySpaceBetweenRows(true);
        cfg.setCollapseRowSpan(false);
        ex.setConfiguration(cfg);
        ex.exportReport();
    }

    private void exportXls(JasperPrint jp, OutputStream out) throws JRException {
        JRXlsExporter ex = new JRXlsExporter();
        ex.setExporterInput(new SimpleExporterInput(jp));
        ex.setExporterOutput(new SimpleOutputStreamExporterOutput(out));
        SimpleXlsReportConfiguration cfg = new SimpleXlsReportConfiguration();
        cfg.setOnePagePerSheet(false);
        cfg.setDetectCellType(true);
        cfg.setRemoveEmptySpaceBetweenRows(true);
        ex.setConfiguration(cfg);
        ex.exportReport();
    }

    private void exportHtml(JasperPrint jp, OutputStream out) throws JRException {
        HtmlExporter ex = new HtmlExporter();
        ex.setExporterInput(new SimpleExporterInput(jp));
        ex.setExporterOutput(new SimpleHtmlExporterOutput(out));
        ex.exportReport();
    }

    private void exportDocx(JasperPrint jp, OutputStream out) throws JRException {
        JRDocxExporter ex = new JRDocxExporter();
        ex.setExporterInput(new SimpleExporterInput(jp));
        ex.setExporterOutput(new SimpleOutputStreamExporterOutput(out));
        ex.exportReport();
    }

    private void exportOdt(JasperPrint jp, OutputStream out) throws JRException {
        JROdtExporter ex = new JROdtExporter();
        ex.setExporterInput(new SimpleExporterInput(jp));
        ex.setExporterOutput(new SimpleOutputStreamExporterOutput(out));
        SimpleOdtReportConfiguration cfg = new SimpleOdtReportConfiguration();
        ex.setConfiguration(cfg);
        ex.exportReport();
    }

    private void exportPptx(JasperPrint jp, OutputStream out) throws JRException {
        JRPptxExporter ex = new JRPptxExporter();
        ex.setExporterInput(new SimpleExporterInput(jp));
        ex.setExporterOutput(new SimpleOutputStreamExporterOutput(out));
        SimplePptxReportConfiguration cfg = new SimplePptxReportConfiguration();
        ex.setConfiguration(cfg);
        ex.exportReport();
    }

    private void exportRtf(JasperPrint jp, OutputStream out) throws JRException {
        JRRtfExporter ex = new JRRtfExporter();
        ex.setExporterInput(new SimpleExporterInput(jp));
        ex.setExporterOutput(new SimpleWriterExporterOutput(
                new OutputStreamWriter(out, StandardCharsets.UTF_8)));
        ex.exportReport();
    }

    private void exportCsv(JasperPrint jp, OutputStream out) throws JRException {
        JRCsvExporter ex = new JRCsvExporter();
        ex.setExporterInput(new SimpleExporterInput(jp));
        ex.setExporterOutput(new SimpleWriterExporterOutput(
                new OutputStreamWriter(out, StandardCharsets.UTF_8)));
        ex.exportReport();
    }

    private void exportText(JasperPrint jp, OutputStream out) throws JRException {
        JRTextExporter ex = new JRTextExporter();
        ex.setExporterInput(new SimpleExporterInput(jp));
        ex.setExporterOutput(new SimpleWriterExporterOutput(
                new OutputStreamWriter(out, StandardCharsets.UTF_8)));
        SimpleTextReportConfiguration cfg = new SimpleTextReportConfiguration();
        cfg.setCharWidth(6.0f);
        cfg.setCharHeight(10.0f);
        ex.setConfiguration(cfg);
        ex.exportReport();
    }

    private void exportPngZip(JasperPrint jp, OutputStream out) throws JRException, IOException {
        int pages = jp.getPages() == null ? 0 : jp.getPages().size();
        if (pages == 0) {
            throw new ReportGenerationException("Cannot export PNG zip — report has no pages");
        }
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            int width = String.valueOf(pages).length();
            for (int i = 0; i < pages; i++) {
                String name = String.format("page-%0" + width + "d.png", i + 1);
                zip.putNextEntry(new ZipEntry(name));
                renderPageAsPng(jp, i, 1.0f, zip);
                zip.closeEntry();
            }
        }
    }

    private void renderPageAsPng(JasperPrint jp, int pageIndex, float zoom, OutputStream out)
            throws JRException, IOException {
        if (jp.getPages() == null || pageIndex >= jp.getPages().size()) {
            throw new ReportGenerationException(
                    "Page index " + pageIndex + " out of range");
        }
        int w = Math.max(1, Math.round(jp.getPageWidth() * zoom));
        int h = Math.max(1, Math.round(jp.getPageHeight() * zoom));
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = img.createGraphics();
        try {
            g2.setColor(Color.WHITE);
            g2.fillRect(0, 0, w, h);
            JRGraphics2DExporter ex = new JRGraphics2DExporter();
            ex.setExporterInput(new SimpleExporterInput(jp));
            SimpleGraphics2DExporterOutput o = new SimpleGraphics2DExporterOutput();
            o.setGraphics2D(g2);
            ex.setExporterOutput(o);
            SimpleGraphics2DReportConfiguration cfg = new SimpleGraphics2DReportConfiguration();
            cfg.setPageIndex(pageIndex);
            cfg.setZoomRatio(zoom);
            ex.setConfiguration(cfg);
            ex.exportReport();
        } finally {
            g2.dispose();
        }
        if (!ImageIO.write(img, "png", out)) {
            throw new ReportGenerationException("No PNG ImageIO writer available on this JVM");
        }
        log.debug("rendered page {} as PNG ({}x{})", pageIndex, w, h);
    }
}
