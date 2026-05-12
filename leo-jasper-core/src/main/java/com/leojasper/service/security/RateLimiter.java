package com.leojasper.service.security;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Token-bucket per identifier (license keyId or "auth-failures:<ip>").
 * 60 requests/minute by default; auto-refills.
 *
 * <p>Also tracks {@link #recordAuthFailure} / {@link #isAuthLocked} so we can
 * temporarily lock out a keyId after repeated failed-auth attempts.
 */
public class RateLimiter {

    private final ConcurrentHashMap<String, Bucket> buckets   = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, FailCounter> fails = new ConcurrentHashMap<>();

    private final int  capacity;            // tokens in a full bucket
    private final long refillIntervalNanos; // time to fully refill
    private final int  authLockThreshold;   // failed attempts before lock
    private final long authLockDurationNanos;

    public RateLimiter() { this(60, 60_000L, 5, 5 * 60_000L); }

    public RateLimiter(int requestsPerWindow, long windowMillis,
                       int authLockThreshold, long authLockMillis) {
        this.capacity = Math.max(1, requestsPerWindow);
        this.refillIntervalNanos = Math.max(1, windowMillis) * 1_000_000L;
        this.authLockThreshold = Math.max(1, authLockThreshold);
        this.authLockDurationNanos = Math.max(1, authLockMillis) * 1_000_000L;
    }

    /** Try to consume a token; returns true if allowed, false if rate-limited. */
    public boolean tryAcquire(String key) {
        Bucket b = buckets.computeIfAbsent(key, k -> new Bucket(capacity, System.nanoTime()));
        synchronized (b) {
            long now = System.nanoTime();
            long elapsed = now - b.lastRefill;
            if (elapsed > 0) {
                long refill = (elapsed * capacity) / refillIntervalNanos;
                if (refill > 0) {
                    b.tokens = Math.min(capacity, (int) (b.tokens + refill));
                    b.lastRefill = now;
                }
            }
            if (b.tokens <= 0) return false;
            b.tokens--;
            return true;
        }
    }

    public void recordAuthFailure(String identifier) {
        FailCounter f = fails.computeIfAbsent(identifier, k -> new FailCounter());
        f.count.incrementAndGet();
        f.lastFailureNanos.set(System.nanoTime());
    }

    public boolean isAuthLocked(String identifier) {
        FailCounter f = fails.get(identifier);
        if (f == null) return false;
        long sinceLast = System.nanoTime() - f.lastFailureNanos.get();
        if (sinceLast > authLockDurationNanos) {
            // Window has passed — reset.
            fails.remove(identifier);
            return false;
        }
        return f.count.get() >= authLockThreshold;
    }

    public void clearAuthFailures(String identifier) { fails.remove(identifier); }

    private static class Bucket {
        int tokens;
        long lastRefill;
        Bucket(int t, long r) { tokens = t; lastRefill = r; }
    }

    private static class FailCounter {
        final AtomicInteger count = new AtomicInteger();
        final AtomicLong lastFailureNanos = new AtomicLong();
    }
}
