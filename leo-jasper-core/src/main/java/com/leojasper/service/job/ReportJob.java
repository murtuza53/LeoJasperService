package com.leojasper.service.job;

import java.time.Instant;

public class ReportJob {

    private final String id;
    private final String templateId;
    private final String inputFormat;
    private final String outputFormat;
    private final Instant submittedAt;

    private volatile ReportJobStatus status = ReportJobStatus.PENDING;
    private volatile Instant startedAt;
    private volatile Instant finishedAt;
    private volatile byte[] result;
    private volatile String contentType;
    private volatile String filename;
    private volatile String errorMessage;

    public ReportJob(String id, String templateId, String inputFormat, String outputFormat) {
        this.id = id;
        this.templateId = templateId;
        this.inputFormat = inputFormat;
        this.outputFormat = outputFormat;
        this.submittedAt = Instant.now();
    }

    public String getId() { return id; }
    public String getTemplateId() { return templateId; }
    public String getInputFormat() { return inputFormat; }
    public String getOutputFormat() { return outputFormat; }
    public Instant getSubmittedAt() { return submittedAt; }
    public ReportJobStatus getStatus() { return status; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public byte[] getResult() { return result; }
    public String getContentType() { return contentType; }
    public String getFilename() { return filename; }
    public String getErrorMessage() { return errorMessage; }

    public void markRunning() {
        this.status = ReportJobStatus.RUNNING;
        this.startedAt = Instant.now();
    }

    public void markCompleted(byte[] bytes, String contentType, String filename) {
        this.result = bytes;
        this.contentType = contentType;
        this.filename = filename;
        this.status = ReportJobStatus.COMPLETED;
        this.finishedAt = Instant.now();
    }

    public void markFailed(String message) {
        this.errorMessage = message;
        this.status = ReportJobStatus.FAILED;
        this.finishedAt = Instant.now();
    }

    public void markCancelled() {
        this.status = ReportJobStatus.CANCELLED;
        this.finishedAt = Instant.now();
    }
}
