package com.leojasper.service.pdf;

import com.leojasper.service.ReportGenerationException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Stamps a diagonal watermark across each page using PDFBox. Useful for
 * "DRAFT" / "CONFIDENTIAL" labels.
 */
public class WatermarkPostProcessor implements PdfPostProcessor {

    private final String text;
    private final float opacity;
    private final Color color;
    private final float fontSize;

    public WatermarkPostProcessor(String text) {
        this(text, 0.18f, new Color(120, 120, 120), 84f);
    }

    public WatermarkPostProcessor(String text, float opacity, Color color, float fontSize) {
        this.text = text;
        this.opacity = opacity;
        this.color = color;
        this.fontSize = fontSize;
    }

    @Override
    public byte[] process(byte[] pdf) {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

            PDExtendedGraphicsState gs = new PDExtendedGraphicsState();
            gs.setNonStrokingAlphaConstant(opacity);
            gs.setStrokingAlphaConstant(opacity);

            for (PDPage page : doc.getPages()) {
                float w = page.getMediaBox().getWidth();
                float h = page.getMediaBox().getHeight();

                try (PDPageContentStream cs = new PDPageContentStream(
                        doc, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                    cs.setGraphicsStateParameters(gs);
                    cs.setNonStrokingColor(color);
                    cs.beginText();
                    cs.setFont(font, fontSize);
                    cs.setTextMatrix(
                            new org.apache.pdfbox.util.Matrix(
                                    (float) Math.cos(Math.PI / 6), (float) Math.sin(Math.PI / 6),
                                    (float) -Math.sin(Math.PI / 6), (float) Math.cos(Math.PI / 6),
                                    w / 2f - fontSize * text.length() / 4f, h / 2f - fontSize / 2f));
                    cs.showText(text);
                    cs.endText();
                }
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream(pdf.length + 1024);
            doc.save(baos);
            return baos.toByteArray();
        } catch (IOException e) {
            throw new ReportGenerationException("Watermarking failed: " + e.getMessage(), e);
        }
    }
}
