package com.leojasper.service.image;

import net.sf.jasperreports.engine.util.FileResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Resolves JRXML image references against a company's asset folder.
 *
 * <p>Resolution order for a given filename:
 * <ol>
 *   <li>HTTP/HTTPS/data URIs — return {@code null} so JR loads the URL natively.</li>
 *   <li>Absolute filesystem paths under {@code companyDir} — returned as-is.
 *       Paths outside the company dir are rejected (otherwise a malicious JRXML
 *       could read arbitrary files).</li>
 *   <li>Strip any directory prefix and just use the basename. Look for that
 *       basename under {@code companyDir/images/}, {@code companyDir/}, and
 *       finally each bucket — first hit wins.</li>
 *   <li>Nothing matched — return the cached "Resource not found" placeholder
 *       so the report renders something instead of failing.</li>
 * </ol>
 *
 * <p>The {@link FileResolver} interface is deprecated in newer JR releases
 * in favour of {@code RepositoryService}, but it is still the documented way
 * to plug into JR via the {@code REPORT_FILE_RESOLVER} parameter, and it
 * works on every JR version we target.
 */
@SuppressWarnings("deprecation")
public class CompanyAssetFileResolver implements FileResolver {

    private static final Logger log = LoggerFactory.getLogger(CompanyAssetFileResolver.class);
    private static final List<String> BUCKETS = List.of("images", "templates", "data", "other");

    private final Path companyDir;

    /** Lazily built once per JVM; the same File is returned for every miss. */
    private static volatile File PLACEHOLDER;

    public CompanyAssetFileResolver(Path companyDir) {
        this.companyDir = companyDir == null ? null : companyDir.toAbsolutePath().normalize();
    }

    @Override
    public File resolveFile(String fileName) {
        if (fileName == null || fileName.isBlank()) return placeholder();

        String trimmed = fileName.trim();

        // URLs are not file resolution — let JR handle them.
        String lower = trimmed.toLowerCase();
        if (lower.startsWith("http://") || lower.startsWith("https://")
                || lower.startsWith("data:") || lower.startsWith("ftp://")) {
            return null;
        }

        if (companyDir == null) {
            log.debug("no companyDir bound — returning placeholder for '{}'", trimmed);
            return placeholder();
        }

        // 2. Absolute path — only honor it if it stays within companyDir.
        try {
            Path asPath = Path.of(trimmed).toAbsolutePath().normalize();
            if (asPath.startsWith(companyDir) && Files.exists(asPath)) {
                return asPath.toFile();
            }
        } catch (Exception ignored) { /* fall through */ }

        // 3. Strip directory components; basename search.
        String basename = trimmed;
        int slash = Math.max(basename.lastIndexOf('/'), basename.lastIndexOf('\\'));
        if (slash >= 0) basename = basename.substring(slash + 1);
        // Strip any query/fragment that might have slipped through.
        int q = basename.indexOf('?');
        if (q >= 0) basename = basename.substring(0, q);
        if (basename.isBlank()) return placeholder();

        // Look in images/ first (most common), then directly under companyDir,
        // then every other bucket.
        File hit = firstExisting(companyDir.resolve("images").resolve(basename));
        if (hit != null) return hit;
        hit = firstExisting(companyDir.resolve(basename));
        if (hit != null) return hit;
        for (String bucket : BUCKETS) {
            if ("images".equals(bucket)) continue;
            hit = firstExisting(companyDir.resolve(bucket).resolve(basename));
            if (hit != null) return hit;
        }

        log.debug("asset '{}' not found under {} — returning placeholder", basename, companyDir);
        return placeholder();
    }

    private File firstExisting(Path p) {
        return Files.isRegularFile(p) ? p.toFile() : null;
    }

    /**
     * Public accessor for the cached "Resource not found" placeholder File,
     * so adjacent classes (e.g. the RepositoryService) can serve the same
     * bytes for URL fetch failures without re-implementing the build logic.
     */
    public static File placeholderFile() {
        return placeholder();
    }

    /**
     * Returns the cached "Resource not found" placeholder File, generating it
     * on first call. Synchronized so concurrent fills don't all race to build
     * the same temp file.
     */
    private static File placeholder() {
        File p = PLACEHOLDER;
        if (p != null && p.exists()) return p;
        synchronized (CompanyAssetFileResolver.class) {
            p = PLACEHOLDER;
            if (p != null && p.exists()) return p;
            try {
                p = buildPlaceholderFile();
                PLACEHOLDER = p;
                return p;
            } catch (IOException e) {
                log.warn("could not build placeholder PNG: {}", e.getMessage());
                // Last resort — return a non-existent file path. JR will use
                // its built-in icon fallback because of the global on-error
                // property set by LeoJasperService.
                return new File("placeholder-unavailable.png");
            }
        }
    }

    private static File buildPlaceholderFile() throws IOException {
        BufferedImage img = new BufferedImage(300, 80, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                               RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            // soft grey background
            g.setColor(new Color(0xF4, 0xF4, 0xF7));
            g.fillRect(0, 0, 300, 80);
            // dashed border
            g.setColor(new Color(0x99, 0x99, 0x99));
            g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND,
                                        0, new float[]{4f, 4f}, 0));
            g.drawRect(2, 2, 296, 76);
            // centered label
            g.setColor(new Color(0x55, 0x55, 0x55));
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
            String label = "Resource not found";
            FontMetrics fm = g.getFontMetrics();
            int x = (300 - fm.stringWidth(label)) / 2;
            int y = (80 - fm.getHeight()) / 2 + fm.getAscent();
            g.drawString(label, x, y);
        } finally {
            g.dispose();
        }
        File f = File.createTempFile("leojasper-placeholder-", ".png");
        f.deleteOnExit();
        ImageIO.write(img, "png", f);
        log.info("placeholder image written to {}", f);
        return f;
    }
}
