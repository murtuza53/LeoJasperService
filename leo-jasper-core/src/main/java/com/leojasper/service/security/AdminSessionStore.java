package com.leojasper.service.security;

import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory session map keyed by an opaque session id (32 random bytes,
 * base64url). Sessions hold the admin name and last-activity timestamp.
 * Idle timeout = 30 min by default; configurable.
 *
 * <p>Sessions are NOT persisted — restart logs the admin out. Acceptable
 * trade-off for a single-admin install.
 */
public class AdminSessionStore {

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Duration idleTimeout;

    public AdminSessionStore() { this(Duration.ofMinutes(30)); }
    public AdminSessionStore(Duration idleTimeout) { this.idleTimeout = idleTimeout; }

    public String createSession(String adminName) {
        evict();
        String sid = CryptoBox.randomToken(32);
        sessions.put(sid, new Session(adminName, Instant.now()));
        return sid;
    }

    public Optional<Session> validate(String sessionId) {
        if (sessionId == null) return Optional.empty();
        evict();
        Session s = sessions.get(sessionId);
        if (s == null) return Optional.empty();
        if (Instant.now().isAfter(s.lastSeen.plus(idleTimeout))) {
            sessions.remove(sessionId);
            return Optional.empty();
        }
        s.lastSeen = Instant.now();
        return Optional.of(s);
    }

    public void invalidate(String sessionId) {
        if (sessionId != null) sessions.remove(sessionId);
    }

    public void invalidateAll() { sessions.clear(); }

    private void evict() {
        Instant cutoff = Instant.now().minus(idleTimeout);
        Iterator<Map.Entry<String, Session>> it = sessions.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().lastSeen.isBefore(cutoff)) it.remove();
        }
    }

    public static class Session {
        public final String  adminName;
        public volatile Instant lastSeen;
        public Session(String adminName, Instant lastSeen) {
            this.adminName = adminName; this.lastSeen = lastSeen;
        }
    }
}
