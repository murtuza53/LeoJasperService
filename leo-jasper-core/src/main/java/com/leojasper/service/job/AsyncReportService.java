package com.leojasper.service.job;

import com.leojasper.service.LeoJasperService;
import com.leojasper.service.OutputFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Submits report generations to a bounded executor and tracks their progress
 * via a {@link ReportJobStore}. Results are kept in memory by default; swap in
 * a different store for object-storage-backed retrieval.
 */
public class AsyncReportService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AsyncReportService.class);

    private final LeoJasperService service;
    private final ReportJobStore store;
    private final ExecutorService executor;

    public AsyncReportService(LeoJasperService service) {
        this(service, new InMemoryReportJobStore(),
                Math.max(2, Runtime.getRuntime().availableProcessors()));
    }

    public AsyncReportService(LeoJasperService service, ReportJobStore store, int parallelism) {
        this.service = service;
        this.store = store;
        ThreadFactory tf = new ThreadFactory() {
            private final AtomicInteger n = new AtomicInteger();
            @Override public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "leojasper-job-" + n.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };
        this.executor = Executors.newFixedThreadPool(Math.max(1, parallelism), tf);
    }

    public ReportJobStore store() { return store; }

    public ReportJob submit(String templateId, Object inputData,
                            String inputFormat, String outputFormat,
                            Map<String, Object> reportParameters) {
        String id = UUID.randomUUID().toString();
        ReportJob job = new ReportJob(id, templateId, inputFormat, outputFormat);
        store.save(job);
        executor.submit(() -> run(job, null, inputData, reportParameters));
        return job;
    }

    public ReportJob submitInline(byte[] jrxmlBytes, Object inputData,
                                  String inputFormat, String outputFormat,
                                  Map<String, Object> reportParameters) {
        String id = UUID.randomUUID().toString();
        ReportJob job = new ReportJob(id, "<inline>", inputFormat, outputFormat);
        store.save(job);
        executor.submit(() -> run(job, jrxmlBytes, inputData, reportParameters));
        return job;
    }

    private void run(ReportJob job, byte[] jrxmlBytes,
                     Object inputData, Map<String, Object> reportParameters) {
        job.markRunning();
        store.save(job);
        try {
            byte[] bytes;
            if (jrxmlBytes != null) {
                bytes = service.generateReportFromJrxml(
                        jrxmlBytes, inputData,
                        job.getInputFormat(), job.getOutputFormat(),
                        reportParameters);
            } else {
                bytes = service.generateReport(
                        job.getTemplateId(), inputData,
                        job.getInputFormat(), job.getOutputFormat(),
                        reportParameters);
            }
            OutputFormat fmt = OutputFormat.from(job.getOutputFormat());
            String filename = "report-" + job.getId() + "." + fmt.fileExtension();
            job.markCompleted(bytes, fmt.contentType(), filename);
        } catch (Exception e) {
            log.warn("Job {} failed: {}", job.getId(), e.getMessage());
            job.markFailed(e.getMessage());
        } finally {
            store.save(job);
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
