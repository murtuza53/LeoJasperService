package com.leojasper.service.image;

import net.sf.jasperreports.engine.JasperReportsContext;
import net.sf.jasperreports.repo.InputStreamResource;
import net.sf.jasperreports.repo.Resource;
import net.sf.jasperreports.repo.StreamRepositoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Path;

/**
 * JR {@link StreamRepositoryService} that resolves image references for one
 * fill:
 *
 * <ul>
 *   <li>{@code http(s)://…} — fetched with a 5 s connect+read timeout. Failures
 *       (DNS, 4xx/5xx, timeout) yield the "Resource not found" placeholder
 *       bytes so the report renders instead of aborting.</li>
 *   <li>{@code "header.png"} / {@code "logo"} / {@code "/Files/Branding/X.png"}
 *       — basename looked up under {@code companyDir/images/} and friends via
 *       {@link CompanyAssetFileResolver}. Misses fall back to the placeholder.</li>
 *   <li>{@code data:} URIs — handled by JR's default decoder.</li>
 * </ul>
 *
 * <p>A fresh instance is created per fill so it can carry a company-specific
 * {@code companyDir}; registered on a per-fill {@code SimpleJasperReportsContext}
 * as a {@code RepositoryService} extension.
 */
public class CompanyAssetRepositoryService implements StreamRepositoryService {

    private static final Logger log = LoggerFactory.getLogger(CompanyAssetRepositoryService.class);

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS    = 5_000;

    private final CompanyAssetFileResolver fileResolver;

    public CompanyAssetRepositoryService(Path companyDir) {
        this.fileResolver = new CompanyAssetFileResolver(companyDir);
    }

    /** JR may instantiate via this constructor when treating us as an extension. */
    @SuppressWarnings("unused")
    public CompanyAssetRepositoryService(JasperReportsContext ctx) {
        this((Path) null);
    }

    // ===== StreamRepositoryService =====

    @Override
    public InputStream getInputStream(String location) {
        if (location == null || location.isBlank()) return null;
        // Only intercept image-like lookups. Subreports (.jasper / .jrxml),
        // properties files, fonts, and anything else with a non-image
        // extension flow through to JR's default services unmodified.
        if (!looksLikeImage(location)) return null;

        String lower = location.toLowerCase();
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            byte[] urlBytes = fetchUrl(location);
            return urlBytes != null ? new ByteArrayInputStream(urlBytes) : openPlaceholder();
        }
        if (lower.startsWith("data:")) {
            // data: URIs — let JR decode them itself
            return null;
        }

        File f = fileResolver.resolveFile(location);
        if (f == null || !f.exists()) return openPlaceholder();
        try {
            return new FileInputStream(f);
        } catch (IOException e) {
            log.debug("could not open resolved file {}: {}", f, e.getMessage());
            return openPlaceholder();
        }
    }

    /**
     * Heuristic: handle URLs, paths with image extensions, and paths without
     * any extension (a common JRXML idiom for image refs like {@code "MTC_LOGO"}).
     * Anything else returns null and JR falls through to its default services.
     */
    private boolean looksLikeImage(String location) {
        String lower = location.toLowerCase();
        if (lower.startsWith("http://") || lower.startsWith("https://")
                || lower.startsWith("data:")) return true;
        // Strip any query/fragment before examining the extension
        int q = lower.indexOf('?');
        if (q >= 0) lower = lower.substring(0, q);
        int slash = Math.max(lower.lastIndexOf('/'), lower.lastIndexOf('\\'));
        String basename = slash >= 0 ? lower.substring(slash + 1) : lower;
        int dot = basename.lastIndexOf('.');
        if (dot < 0) return true;     // no extension — treat as image reference
        String ext = basename.substring(dot);
        switch (ext) {
            case ".png": case ".jpg": case ".jpeg":
            case ".gif": case ".svg": case ".webp":
            case ".bmp": case ".tif": case ".tiff":
            case ".ico":
                return true;
            default:
                return false;
        }
    }

    @Override
    public OutputStream getOutputStream(String location) {
        throw new UnsupportedOperationException(
                "CompanyAssetRepositoryService is read-only");
    }

    // ===== RepositoryService =====

    @Override
    public Resource getResource(String location) {
        return wrapAsResource(getInputStream(location));
    }

    @Override
    @SuppressWarnings("unchecked")
    public <K extends Resource> K getResource(String location, Class<K> resourceType) {
        if (resourceType == null) return null;
        if (InputStreamResource.class.isAssignableFrom(resourceType)) {
            return (K) wrapAsResource(getInputStream(location));
        }
        return null;
    }

    @Override
    public void saveResource(String location, Resource resource) {
        throw new UnsupportedOperationException(
                "CompanyAssetRepositoryService is read-only");
    }

    // ===== helpers =====

    private InputStreamResource wrapAsResource(InputStream in) {
        if (in == null) return null;
        InputStreamResource r = new InputStreamResource();
        r.setInputStream(in);
        return r;
    }

    private InputStream openPlaceholder() {
        try {
            return new FileInputStream(CompanyAssetFileResolver.placeholderFile());
        } catch (IOException e) {
            // Last-resort fallback — a 1×1 transparent PNG embedded in the class.
            return new ByteArrayInputStream(ONE_BY_ONE_PNG);
        }
    }

    private byte[] fetchUrl(String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URI(url).toURL().openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            if (code / 100 != 2) {
                log.debug("URL fetch {} returned HTTP {}", url, code);
                return null;
            }
            try (InputStream in = conn.getInputStream()) {
                return in.readAllBytes();
            }
        } catch (Exception e) {
            log.debug("URL fetch {} failed: {}", url, e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** Minimal 1×1 transparent PNG — last-resort fallback if even the
     *  placeholder file can't be opened. */
    private static final byte[] ONE_BY_ONE_PNG = new byte[] {
        (byte)0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
        0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
        0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, (byte)0xC4,
        (byte)0x89, 0x00, 0x00, 0x00, 0x0D, 0x49, 0x44, 0x41,
        0x54, 0x78, (byte)0x9C, 0x62, 0x00, 0x01, 0x00, 0x00,
        0x05, 0x00, 0x01, 0x0D, 0x0A, 0x2D, (byte)0xB4, 0x00,
        0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, (byte)0xAE,
        0x42, 0x60, (byte)0x82
    };
}
