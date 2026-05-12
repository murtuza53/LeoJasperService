package com.leojasper.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;

/**
 * Lightweight wrapper around Micrometer. By default it routes to a no-op
 * {@link CompositeMeterRegistry} so the core is usable without Spring Boot.
 *
 * <p>Timers and counters are registered lazily (on first record) with the
 * appropriate tags — this avoids the "name registered without tags" trap where
 * the first un-tagged registration prevents later tagged registrations.
 */
public class ReportMetrics {

    private final MeterRegistry registry;

    public ReportMetrics() { this(new CompositeMeterRegistry()); }
    public ReportMetrics(MeterRegistry registry) { this.registry = registry; }

    public Timer.Sample startTimer() { return Timer.start(registry); }

    public void recordCompile(Timer.Sample sample) {
        sample.stop(Timer.builder("leojasper.compile")
                .description("JRXML compile time")
                .register(registry));
    }

    public void recordFill(Timer.Sample sample, String inputFormat) {
        sample.stop(Timer.builder("leojasper.fill")
                .description("Report fill time")
                .tags(Tags.of("inputFormat", inputFormat))
                .register(registry));
    }

    public void recordExport(Timer.Sample sample, String outputFormat) {
        sample.stop(Timer.builder("leojasper.export")
                .description("Report export time")
                .tags(Tags.of("outputFormat", outputFormat))
                .register(registry));
    }

    public void incrementError(String stage) {
        Counter.builder("leojasper.errors")
                .description("Report generation errors")
                .tags(Tags.of("stage", stage))
                .register(registry)
                .increment();
    }

    public MeterRegistry getRegistry() { return registry; }
}
