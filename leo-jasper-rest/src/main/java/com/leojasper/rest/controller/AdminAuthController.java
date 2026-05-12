package com.leojasper.rest.controller;

import com.leojasper.rest.security.AdminAuthFilter;
import com.leojasper.service.ReportGenerationException;
import com.leojasper.service.security.AuthService;
import com.leojasper.service.security.CryptoBox;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminAuthController {

    private final AuthService auth;

    public AdminAuthController(AuthService auth) { this.auth = auth; }

    @GetMapping("/status")
    public Map<String, Object> status(HttpServletRequest req) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("hasCredentialsFile", auth.credentialsStore().exists());
        m.put("loggedIn", auth.isLoggedIn());
        m.put("companyRegistryLoaded", auth.registry().isLoaded());
        m.put("assetsRoot", auth.adminFile() == null ? null : auth.adminFile().assetsRoot);
        // Indicate whether the current request carries a valid session
        String sid = readCookie(req, AdminAuthFilter.COOKIE_NAME);
        m.put("session", auth.sessions().validate(sid).isPresent());

        // Surface the absolute backup paths so the admin can see exactly what to
        // include in their backup process. effectiveAssetsRoot() falls back to
        // the configured default until the admin sets one through the UI.
        Map<String, Object> backup = new LinkedHashMap<>();
        backup.put("credentialsFile", auth.credentialsStore().file().toString());
        backup.put("companiesFile",   auth.registry().file().toString());
        backup.put("assetsRoot",      auth.effectiveAssetsRoot().toString());
        backup.put("note",
            "Back up the two .enc files and the assets root TOGETHER. They share " +
            "an encryption key derived from the admin password — restoring one " +
            "without the other is useless.");
        m.put("backup", backup);
        return m;
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Map<String, String> body, HttpServletResponse res) {
        String username  = body.getOrDefault("username", "");
        String password  = body.getOrDefault("password", "");
        String vaultPath = body.getOrDefault("vaultPath", "");
        if (username.isBlank() || password.isBlank()) {
            throw new ReportGenerationException("username and password are required");
        }
        AuthService.LoginResult r;
        try {
            r = auth.login(username, password.toCharArray(),
                           vaultPath.isBlank() ? null : vaultPath);
        } catch (CryptoBox.BadPasswordException e) {
            throw new ReportGenerationException("Invalid credentials");
        }
        Cookie c = new Cookie(AdminAuthFilter.COOKIE_NAME, r.sessionId);
        c.setHttpOnly(true);
        c.setPath("/");
        c.setMaxAge(-1);   // session cookie
        res.addCookie(c);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("mustChangePassword", r.mustChangePassword);
        out.put("mustSetAssetsRoot",  r.mustSetAssetsRoot);
        out.put("hasCompanies",       r.hasCompanies);
        out.put("assetsRoot",         r.assetsRoot);
        return out;
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(HttpServletRequest req, HttpServletResponse res) {
        String sid = readCookie(req, AdminAuthFilter.COOKIE_NAME);
        auth.logout(sid);
        Cookie c = new Cookie(AdminAuthFilter.COOKIE_NAME, "");
        c.setHttpOnly(true); c.setPath("/"); c.setMaxAge(0);
        res.addCookie(c);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        return out;
    }

    @PostMapping("/change-password")
    public Map<String, Object> changePassword(@RequestBody Map<String, String> body) {
        String current = body.getOrDefault("currentPassword", "");
        String next    = body.getOrDefault("newPassword", "");
        if (current.isBlank() || next.isBlank()) {
            throw new ReportGenerationException("currentPassword and newPassword are required");
        }
        auth.changePassword(current.toCharArray(), next.toCharArray());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        return out;
    }

    @PostMapping("/assets-root")
    public Map<String, Object> setAssetsRoot(@RequestBody Map<String, String> body) {
        String path = body.getOrDefault("path", "");
        java.nio.file.Path resolved = auth.setAssetsRoot(path);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("assetsRoot", resolved.toString());
        return out;
    }

    private String readCookie(HttpServletRequest req, String name) {
        if (req.getCookies() == null) return null;
        for (Cookie c : req.getCookies()) if (name.equals(c.getName())) return c.getValue();
        return null;
    }
}
