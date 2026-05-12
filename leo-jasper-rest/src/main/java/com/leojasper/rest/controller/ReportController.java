package com.leojasper.rest.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leojasper.service.LeoJasperService;
import com.leojasper.service.OutputFormat;
import com.leojasper.service.ReportGenerationException;
import com.leojasper.service.job.AsyncReportService;
import com.leojasper.service.job.ReportJob;
import com.leojasper.service.job.ReportJobStore;
import com.leojasper.service.template.TemplateRegistry;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sync + async report endpoints. The client supplies the template either by
 * id (resolved by the configured TemplateRegistry) or as an uploaded multipart
 * file ({@code template}) — uploaded JRXMLs go through the inline-bytes path
 * so they aren't persisted into the registry.
 */
@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private final LeoJasperService service;
    private final AsyncReportService asyncService;
    private final ReportJobStore jobStore;
    private final TemplateRegistry registry;
    private final ObjectMapper jackson = new ObjectMapper();

    public ReportController(LeoJasperService service,
                            AsyncReportService asyncService,
                            ReportJobStore jobStore,
                            TemplateRegistry registry) {
        this.service = service;
        this.asyncService = asyncService;
        this.jobStore = jobStore;
        this.registry = registry;
    }

    @PostMapping(value = "/render", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> render(
            @RequestParam(value = "templateId", required = false) String templateId,
            @RequestParam(value = "template",   required = false) MultipartFile templateFile,
            @RequestParam(value = "data",       required = false) MultipartFile dataFile,
            @RequestParam(value = "dataInline", required = false) String dataInline,
            @RequestParam(value = "inputFormat", required = false) String inputFormat,
            @RequestParam("outputFormat") String outputFormat,
            @RequestParam(value = "parameters", required = false) String parametersJson) throws IOException {

        Object inputData = resolveInputData(dataFile, dataInline);
        Map<String, Object> params = parseParameters(parametersJson);
        OutputFormat fmt = OutputFormat.from(outputFormat);

        byte[] bytes;
        if (templateFile != null && !templateFile.isEmpty()) {
            bytes = service.generateReportFromJrxml(
                    templateFile.getBytes(), inputData, inputFormat, outputFormat, params);
        } else {
            requireTemplateId(templateId);
            bytes = service.generateReport(
                    templateId, inputData, inputFormat, outputFormat, params);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.CONTENT_TYPE, fmt.contentType());
        headers.add(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"report." + fmt.fileExtension() + "\"");
        return new ResponseEntity<>(bytes, headers, 200);
    }

    @PostMapping(value = "/render-async", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> renderAsync(
            @RequestParam(value = "templateId", required = false) String templateId,
            @RequestParam(value = "template",   required = false) MultipartFile templateFile,
            @RequestParam(value = "data",       required = false) MultipartFile dataFile,
            @RequestParam(value = "dataInline", required = false) String dataInline,
            @RequestParam(value = "inputFormat", required = false) String inputFormat,
            @RequestParam("outputFormat") String outputFormat,
            @RequestParam(value = "parameters", required = false) String parametersJson) throws IOException {

        Object inputData = resolveInputData(dataFile, dataInline);
        if (inputData instanceof InputStream) {
            inputData = ((InputStream) inputData).readAllBytes();
        }
        Map<String, Object> params = parseParameters(parametersJson);

        ReportJob job;
        if (templateFile != null && !templateFile.isEmpty()) {
            job = asyncService.submitInline(
                    templateFile.getBytes(), inputData, inputFormat, outputFormat, params);
        } else {
            requireTemplateId(templateId);
            job = asyncService.submit(
                    templateId, inputData, inputFormat, outputFormat, params);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jobId", job.getId());
        body.put("status", job.getStatus().name());
        return ResponseEntity.accepted()
                .header("X-Job-Id", job.getId())
                .header(HttpHeaders.LOCATION, "/api/reports/jobs/" + job.getId())
                .body(body);
    }

    @GetMapping("/jobs")
    public List<Map<String, Object>> recentJobs(@RequestParam(defaultValue = "20") int limit) {
        return jobStore.recent(limit).stream().map(this::jobView).toList();
    }

    @GetMapping("/jobs/{id}")
    public ResponseEntity<Map<String, Object>> jobStatus(@PathVariable String id) {
        return jobStore.find(id)
                .map(j -> ResponseEntity.ok(jobView(j)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/jobs/{id}/result")
    public ResponseEntity<byte[]> jobResult(@PathVariable String id) {
        ReportJob job = jobStore.find(id).orElseThrow(
                () -> new ReportGenerationException("No such job: " + id));
        if (job.getResult() == null) {
            return ResponseEntity.status(409)
                    .body(("Job not complete (status=" + job.getStatus() + ")")
                            .getBytes(StandardCharsets.UTF_8));
        }
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.CONTENT_TYPE, job.getContentType());
        headers.add(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + job.getFilename() + "\"");
        return new ResponseEntity<>(job.getResult(), headers, 200);
    }

    @GetMapping("/formats")
    public Map<String, Object> formats() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("input", List.of("json", "xml", "csv", "sql", "beans"));
        out.put("output", List.of(
                "pdf", "xlsx", "xls", "html", "png", "png-zip",
                "docx", "odt", "rtf", "csv", "text", "pptx"));
        return out;
    }

    // ----------------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------------

    private void requireTemplateId(String templateId) {
        if (templateId == null || templateId.isBlank()) {
            throw new ReportGenerationException(
                    "Provide either 'template' (multipart file) or 'templateId'");
        }
        if (!registry.exists(templateId)) {
            throw new ReportGenerationException("Unknown templateId: " + templateId);
        }
    }

    private Object resolveInputData(MultipartFile dataFile, String dataInline) throws IOException {
        if (dataFile != null && !dataFile.isEmpty()) {
            return dataFile.getBytes();
        }
        if (dataInline != null && !dataInline.isEmpty()) {
            return dataInline;
        }
        return new byte[0];
    }

    private Map<String, Object> parseParameters(String json) throws IOException {
        if (json == null || json.isBlank()) return new HashMap<>();
        return jackson.readValue(json, new TypeReference<Map<String, Object>>() {});
    }

    private Map<String, Object> jobView(ReportJob j) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", j.getId());
        m.put("status", j.getStatus().name());
        m.put("templateId", j.getTemplateId());
        m.put("inputFormat", j.getInputFormat());
        m.put("outputFormat", j.getOutputFormat());
        m.put("submittedAt", j.getSubmittedAt());
        m.put("startedAt", j.getStartedAt());
        m.put("finishedAt", j.getFinishedAt());
        m.put("contentType", j.getContentType());
        m.put("filename", j.getFilename());
        m.put("error", j.getErrorMessage());
        return m;
    }
}
