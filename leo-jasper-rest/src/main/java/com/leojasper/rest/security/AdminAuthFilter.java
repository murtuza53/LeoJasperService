package com.leojasper.rest.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leojasper.service.security.AdminSessionStore;
import com.leojasper.service.security.AuthService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Gates {@code /api/admin/*} (except {@code /api/admin/login}) on a valid
 * admin session cookie. Cookie name: {@code LEOJASPER_ADMIN_SID}.
 */
public class AdminAuthFilter extends OncePerRequestFilter {

    public static final String COOKIE_NAME = "LEOJASPER_ADMIN_SID";

    private final AuthService authService;
    private final ObjectMapper jackson = new ObjectMapper();

    public AdminAuthFilter(AuthService authService) { this.authService = authService; }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        String path = req.getRequestURI();
        if (path == null) return true;
        if (!path.startsWith("/api/admin")) return true;
        // open endpoints
        return path.equals("/api/admin/login")
            || path.equals("/api/admin/status");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String sid = readCookie(req, COOKIE_NAME);
        Optional<AdminSessionStore.Session> sess = authService.sessions().validate(sid);
        if (sess.isEmpty()) {
            sendError(res, 401, "Admin session required");
            return;
        }
        chain.doFilter(req, res);
    }

    private String readCookie(HttpServletRequest req, String name) {
        if (req.getCookies() == null) return null;
        for (Cookie c : req.getCookies()) if (name.equals(c.getName())) return c.getValue();
        return null;
    }

    private void sendError(HttpServletResponse res, int status, String message) throws IOException {
        res.setStatus(status);
        res.setContentType("application/json");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        body.put("error", "Unauthorized");
        body.put("message", message);
        body.put("timestamp", Instant.now().toString());
        res.getWriter().write(jackson.writeValueAsString(body));
    }
}
