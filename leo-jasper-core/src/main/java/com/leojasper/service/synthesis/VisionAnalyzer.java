package com.leojasper.service.synthesis;

/**
 * SPI for document-layout analysis. Implementations send a rendered page
 * (PNG bytes) to a vision model and return a populated {@link LayoutModel}.
 */
public interface VisionAnalyzer {

    /**
     * @param pngBytes  PNG-encoded image of the document (page 1 for PDFs)
     * @param userHint  optional context from the user, e.g. "this is an invoice"
     * @return populated layout model — never null, but fields may be empty
     *         if the model couldn't recover them
     */
    LayoutModel analyze(byte[] pngBytes, String userHint);

    /** Identifier used in API responses and logs (e.g. "gemini", "mock"). */
    String name();
}
