package com.leojasper.rest.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leojasper.service.security.AuditLogger;
import com.leojasper.service.security.AuthService;
import com.leojasper.service.security.Company;
import com.leojasper.service.security.CompanyRegistry;
import com.leojasper.service.security.LicenseContext;
import com.leojasper.service.security.LicenseScope;
import com.leojasper.service.security.RateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Validates {@code X-Key-Id} + {@code X-Key-Secret} on every {@code /api/}
 * request other than {@code /api/admin/*} and {@code /api/auth/*}. On
 * success, populates the per-thread {@link LicenseContext} and audits.
 *
 * <p>Tier 2 (HMAC) is not enforced by this filter directly — when a company
 * has {@code hmacRequired = true}, the filter checks for {@code X-Signature}
 * + {@code X-Timestamp} headers as an alternative to {@code X-Key-Secret}.
 */
public class LicenseFilter extends OncePerRequestFilter {

    public static final String HEADER_KEY_ID     = "X-Key-Id";
    public static final String HEADER_KEY_SECRET = "X-Key-Secret";
    public static final String HEADER_TIMESTAMP  = "X-Timestamp";
    public static final String HEADER_SIGNATURE  = "X-Signature";

    private static final Logger log = LoggerFactory.getLogger(LicenseFilter.class);

    private final AuthService    auth;
    private final RateLimiter    rateLimiter;
    private final AuditLogger    auditLogger;
    private final ObjectMapper   jackson = new ObjectMapper();

    public LicenseFilter(AuthService auth, RateLimiter rateLimiter, AuditLogger auditLogger) {
        this.auth = auth;
        this.rateLimiter = rateLimiter;
        this.auditLogger = auditLogger;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        String path = req.getRequestURI();
        if (path == null) return true;
        if (!path.startsWith("/api/")) return true;
        // Admin and bootstrap paths are gated separately.
        if (path.startsWith("/api/admin")) return true;
        if (path.startsWith("/api/auth"))  return true;
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        long t0 = System.nanoTime();
        String keyId  = req.getHeader(HEADER_KEY_ID);
        String secret = req.getHeader(HEADER_KEY_SECRET);
        String ip     = clientIp(req);

        // 1) The registry has to be unlocked (admin must have logged in)
        CompanyRegistry registry = auth.registry();
        if (!registry.isLoaded()) {
            sendError(res, 503, "Service is locked — admin must log in first");
            return;
        }

        // 2) Headers present?
        if (keyId == null || keyId.isBlank()) {
            sendError(res, 401, "Missing " + HEADER_KEY_ID + " header");
            return;
        }
        // Pre-auth IP throttle so we can't be probed indefinitely
        if (rateLimiter.isAuthLocked("ip:" + ip)) {
            sendError(res, 429, "Too many failed auth attempts from this client");
            return;
        }

        // 3) Lookup company
        Optional<Company> oc = registry.findByKeyId(keyId);
        if (oc.isEmpty() || oc.get().disabled) {
            rateLimiter.recordAuthFailure("ip:" + ip);
            sendError(res, 401, "Unknown or disabled keyId");
            return;
        }
        Company company = oc.get();

        // 4) Authenticate (Tier 1 secret OR Tier 2 HMAC)
        boolean ok;
        if (company.hmacRequired) {
            ok = verifyHmac(req, company);
        } else {
            ok = secret != null && registry.verifySecret(company, secret);
        }
        if (!ok) {
            rateLimiter.recordAuthFailure("ip:" + ip);
            rateLimiter.recordAuthFailure("key:" + keyId);
            sendError(res, 401, "Invalid credentials for keyId=" + keyId);
            return;
        }
        rateLimiter.clearAuthFailures("ip:" + ip);

        // 5) Per-key rate limit
        if (!rateLimiter.tryAcquire("key:" + keyId)) {
            sendError(res, 429, "Rate limit exceeded for keyId=" + keyId);
            return;
        }

        // 6) Scope check (writes require FULL)
        LicenseScope scope = company.scopeEnum();
        if (!isScopeAllowed(req, scope)) {
            sendError(res, 403, "License scope " + scope + " does not permit " +
                    req.getMethod() + " " + req.getRequestURI());
            return;
        }

        // 7) Build LicenseContext
        Path companyDir = auth.effectiveAssetsRoot()
                .resolve("companies").resolve(company.folderName);
        LicenseContext ctx = new LicenseContext(company.keyId, company.name, scope, companyDir, ip);
        LicenseContext.set(ctx);

        try {
            chain.doFilter(req, res);
        } finally {
            // 8) Audit (best-effort)
            long ms = (System.nanoTime() - t0) / 1_000_000;
            try {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("ip", ip);
                entry.put("method", req.getMethod());
                entry.put("path", req.getRequestURI());
                entry.put("status", res.getStatus());
                entry.put("elapsedMs", ms);
                auditLogger.record(companyDir, entry);
            } catch (Exception e) {
                log.warn("audit log failed: {}", e.getMessage());
            }
            LicenseContext.clear();
        }
    }

    private boolean isScopeAllowed(HttpServletRequest req, LicenseScope scope) {
        String method = req.getMethod();
        String path   = req.getRequestURI();
        if ("GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method)) {
            return scope.canRead();
        }
        if ("POST".equals(method) && path.startsWith("/api/reports/render")) {
            return scope.canRender();
        }
        // Any other write
        return scope.canWrite();
    }

    private boolean verifyHmac(HttpServletRequest req, Company company) {
        // Placeholder — Tier 2 implementation lands here when first HMAC client appears.
        // For now: HMAC-required companies fall through; admin must rotate them to Tier 1
        // until HMAC is implemented.
        return false;
    }

    private String clientIp(HttpServletRequest req) {
        String fwd = req.getHeader("X-Forwarded-For");
        if (fwd != null && !fwd.isBlank()) {
            int comma = fwd.indexOf(',');
            return (comma > 0 ? fwd.substring(0, comma) : fwd).trim();
        }
        return req.getRemoteAddr();
    }

    private void sendError(HttpServletResponse res, int status, String message) throws IOException {
        res.setStatus(status);
        res.setContentType("application/json");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        body.put("error", status >= 500 ? "Server Error" : "Unauthorized");
        body.put("message", message);
        body.put("timestamp", Instant.now().toString());
        res.getWriter().write(jackson.writeValueAsString(body));
    }
}
