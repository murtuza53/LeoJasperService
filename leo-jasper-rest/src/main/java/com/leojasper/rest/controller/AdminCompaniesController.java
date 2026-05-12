package com.leojasper.rest.controller;

import com.leojasper.service.ReportGenerationException;
import com.leojasper.service.security.AssetService;
import com.leojasper.service.security.AuditLogger;
import com.leojasper.service.security.AuthService;
import com.leojasper.service.security.Company;
import com.leojasper.service.security.CompanyRegistry;
import com.leojasper.service.security.LicenseScope;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/companies")
public class AdminCompaniesController {

    private final AuthService    auth;
    private final AssetService   assets;
    private final AuditLogger    audit;

    public AdminCompaniesController(AuthService auth, AssetService assets, AuditLogger audit) {
        this.auth = auth;
        this.assets = assets;
        this.audit = audit;
    }

    /** List companies — secret hashes are never returned. */
    @GetMapping
    public List<Map<String, Object>> list() {
        return auth.registry().list().stream().map(this::view).toList();
    }

    @GetMapping("/{keyId}")
    public Map<String, Object> get(@PathVariable String keyId) {
        Company c = auth.registry().findByKeyId(keyId)
                .orElseThrow(() -> new ReportGenerationException("Unknown keyId: " + keyId));
        return view(c);
    }

    /** Create a new company. The secret is returned ONCE — copy it now. */
    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        String name = (String) body.getOrDefault("name", "");
        String address = (String) body.getOrDefault("address", "");
        LicenseScope scope = LicenseScope.from((String) body.getOrDefault("scope", "FULL"));
        boolean hmac = Boolean.TRUE.equals(body.get("hmacRequired"));
        if (name.isBlank()) throw new ReportGenerationException("name is required");

        if (auth.adminFile() == null || auth.adminFile().assetsRoot == null
                || auth.adminFile().assetsRoot.isBlank()) {
            throw new ReportGenerationException(
                    "Set the assets root before registering companies (POST /api/admin/assets-root)");
        }

        CompanyRegistry.CompanyCreate r = auth.registry().create(name, address, scope, hmac);
        // Create the per-company folder layout under assetsRoot/companies/{folderName}.
        Path companyDir = auth.effectiveAssetsRoot().resolve("companies").resolve(r.company.folderName);
        assets.initLayout(companyDir);

        Map<String, Object> out = view(r.company);
        out.put("companyDir", companyDir.toString());
        // Once-only secret. Make this VERY clear in the UI.
        out.put("secret", r.secret);
        out.put("note", "Secret shown only once — copy it now. To get a new one, rotate.");
        return out;
    }

    @PostMapping("/{keyId}/rotate")
    public Map<String, Object> rotate(@PathVariable String keyId) {
        String newSecret = auth.registry().rotateSecret(keyId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("keyId", keyId);
        out.put("secret", newSecret);
        out.put("note", "Secret shown only once — copy it now. Old secret has been invalidated.");
        return out;
    }

    @PostMapping("/{keyId}/disable")
    public Map<String, Object> disable(@PathVariable String keyId) {
        auth.registry().setDisabled(keyId, true);
        return Map.of("keyId", keyId, "disabled", true);
    }

    @PostMapping("/{keyId}/enable")
    public Map<String, Object> enable(@PathVariable String keyId) {
        auth.registry().setDisabled(keyId, false);
        return Map.of("keyId", keyId, "disabled", false);
    }

    @PostMapping("/{keyId}")
    public Map<String, Object> update(@PathVariable String keyId, @RequestBody Map<String, Object> body) {
        String name = (String) body.get("name");
        String address = (String) body.get("address");
        LicenseScope scope = body.get("scope") == null
                ? null : LicenseScope.from((String) body.get("scope"));
        boolean hmac = Boolean.TRUE.equals(body.get("hmacRequired"));
        auth.registry().update(keyId, name, address, scope, hmac);
        return view(auth.registry().findByKeyId(keyId).orElseThrow());
    }

    /**
     * Disabling instead of deleting is the default; full delete (which would
     * lose audit history) is intentionally not exposed.
     */
    @DeleteMapping("/{keyId}")
    public Map<String, Object> softDelete(@PathVariable String keyId) {
        return disable(keyId);
    }

    @GetMapping("/{keyId}/audit")
    public List<Map<String, Object>> auditLog(@PathVariable String keyId,
                                              @RequestParam(defaultValue = "100") int limit) {
        Company c = auth.registry().findByKeyId(keyId)
                .orElseThrow(() -> new ReportGenerationException("Unknown keyId: " + keyId));
        Path companyDir = auth.effectiveAssetsRoot().resolve("companies").resolve(c.folderName);
        return audit.recent(companyDir, limit);
    }

    @GetMapping("/{keyId}/trash")
    public List<AssetService.TrashEntry> listTrash(@PathVariable String keyId) {
        Company c = auth.registry().findByKeyId(keyId)
                .orElseThrow(() -> new ReportGenerationException("Unknown keyId: " + keyId));
        Path companyDir = auth.effectiveAssetsRoot().resolve("companies").resolve(c.folderName);
        return assets.listTrash(companyDir);
    }

    @PostMapping("/{keyId}/trash/{trashName}/restore")
    public Map<String, Object> restore(@PathVariable String keyId, @PathVariable String trashName) {
        Company c = auth.registry().findByKeyId(keyId)
                .orElseThrow(() -> new ReportGenerationException("Unknown keyId: " + keyId));
        Path companyDir = auth.effectiveAssetsRoot().resolve("companies").resolve(c.folderName);
        Path restored = assets.restore(companyDir, trashName);
        return Map.of("ok", true, "restored", restored.toString());
    }

    private Map<String, Object> view(Company c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("keyId", c.keyId);
        m.put("name", c.name);
        m.put("address", c.address);
        m.put("scope", c.scope);
        m.put("hmacRequired", c.hmacRequired);
        m.put("disabled", c.disabled);
        m.put("folderName", c.folderName);
        m.put("createdAt", c.createdAt);
        m.put("rotatedAt", c.rotatedAt);
        m.put("disabledAt", c.disabledAt);
        return m;
    }
}
