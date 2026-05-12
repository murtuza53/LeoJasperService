package com.leojasper.service.job;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class InMemoryReportJobStore implements ReportJobStore {

    private final ConcurrentMap<String, ReportJob> jobs = new ConcurrentHashMap<>();
    private final Duration ttl;

    public InMemoryReportJobStore() { this(Duration.ofHours(2)); }

    public InMemoryReportJobStore(Duration ttl) { this.ttl = ttl; }

    @Override
    public void save(ReportJob job) {
        evict();
        jobs.put(job.getId(), job);
    }

    @Override
    public Optional<ReportJob> find(String id) {
        evict();
        return Optional.ofNullable(jobs.get(id));
    }

    @Override
    public List<ReportJob> recent(int limit) {
        evict();
        return jobs.values().stream()
                .sorted(Comparator.comparing(ReportJob::getSubmittedAt).reversed())
                .limit(Math.max(1, limit))
                .toList();
    }

    @Override
    public void delete(String id) { jobs.remove(id); }

    private void evict() {
        Instant cutoff = Instant.now().minus(ttl);
        jobs.values().removeIf(j -> {
            Instant ref = j.getFinishedAt() != null ? j.getFinishedAt() : j.getSubmittedAt();
            return ref.isBefore(cutoff);
        });
    }
}
