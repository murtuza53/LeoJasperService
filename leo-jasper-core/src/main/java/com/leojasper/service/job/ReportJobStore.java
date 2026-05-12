package com.leojasper.service.job;

import java.util.List;
import java.util.Optional;

public interface ReportJobStore {
    void save(ReportJob job);
    Optional<ReportJob> find(String id);
    List<ReportJob> recent(int limit);
    void delete(String id);
}
