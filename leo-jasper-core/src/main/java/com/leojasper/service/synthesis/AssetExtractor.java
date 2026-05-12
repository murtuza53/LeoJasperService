package com.leojasper.service.synthesis;

import com.leojasper.service.ReportGenerationException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Pulls renderable + extractable bytes out of an uploaded document.
 * <ul>
 *   <li>{@link #renderFirstPage(byte[], String)} — produces the PNG sent to the
 *       vision model, regardless of whether the upload was a PDF or an image.</li>
 *   <li>{@link #extractEmbeddedImages(byte[])} — for PDFs only, pulls each
 *       embedded {@code PDImageXObject} out as PNG bytes so the user can
 *       reuse logos / headers / footers in the generated template.</li>
 * </ul>
 */
public class AssetExtractor {

    private static final Logger log = LoggerFactory.getLogger(AssetExtractor.class);

    public byte[] renderFirstPage(byte[] uploadedBytes, String mimeType) {
        try {
            String mt = mimeType == null ? "" : mimeType.toLowerCase();
            if (mt.contains("pdf") || looksLikePdf(uploadedBytes)) {
                return renderPdfFirstPage(uploadedBytes);
            }
            // Already a raster image — re-encode as PNG for consistency.
            BufferedImage img = ImageIO.read(new java.io.ByteArrayInputStream(uploadedBytes));
            if (img == null) {
                throw new ReportGenerationException(
                        "Unsupported upload format (mime=" + mimeType + ")");
            }
            return toPng(img);
        } catch (ReportGenerationException e) {
            throw e;
        } catch (Exception e) {
            throw new ReportGenerationException(
                    "Failed to render first page: " + e.getMessage(), e);
        }
    }

    private byte[] renderPdfFirstPage(byte[] pdfBytes) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            if (doc.getNumberOfPages() == 0) {
                throw new ReportGenerationException("PDF has no pages");
            }
            PDFRenderer renderer = new PDFRenderer(doc);
            BufferedImage img = renderer.renderImageWithDPI(0, 150f, ImageType.RGB);
            return toPng(img);
        }
    }

    public List<ExtractedAsset> extractEmbeddedImages(byte[] uploadedBytes) {
        List<ExtractedAsset> out = new ArrayList<>();
        if (!looksLikePdf(uploadedBytes)) return out;
        try (PDDocument doc = Loader.loadPDF(uploadedBytes)) {
            int idx = 0;
            for (PDPage page : doc.getPages()) {
                PDResources res = page.getResources();
                if (res == null) continue;
                for (COSName name : res.getXObjectNames()) {
                    PDXObject obj;
                    try { obj = res.getXObject(name); }
                    catch (Exception e) { continue; }
                    if (!(obj instanceof PDImageXObject)) continue;
                    PDImageXObject imgX = (PDImageXObject) obj;
                    BufferedImage bi = imgX.getImage();
                    String filename = String.format("asset-%02d.png", ++idx);
                    out.add(new ExtractedAsset(filename, "image/png",
                            bi.getWidth(), bi.getHeight(), toPng(bi)));
                }
            }
        } catch (Exception e) {
            log.warn("Asset extraction failed (returning what we have): {}", e.getMessage());
        }
        return out;
    }

    private boolean looksLikePdf(byte[] bytes) {
        return bytes != null && bytes.length >= 4
                && bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F';
    }

    private byte[] toPng(BufferedImage img) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(64 * 1024);
        if (!ImageIO.write(img, "png", baos)) {
            throw new IOException("No PNG writer available");
        }
        return baos.toByteArray();
    }

    public static class ExtractedAsset {
        public final String name;
        public final String contentType;
        public final int width;
        public final int height;
        public final byte[] bytes;

        public ExtractedAsset(String name, String contentType, int width, int height, byte[] bytes) {
            this.name = name; this.contentType = contentType;
            this.width = width; this.height = height; this.bytes = bytes;
        }
    }
}
