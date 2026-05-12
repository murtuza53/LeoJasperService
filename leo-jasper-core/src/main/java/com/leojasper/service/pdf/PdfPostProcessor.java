package com.leojasper.service.pdf;

@FunctionalInterface
public interface PdfPostProcessor {
    byte[] process(byte[] pdf);
}
