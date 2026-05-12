package com.leojasper.service.pdf;

import com.leojasper.service.ReportGenerationException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Adds AES-256 password protection to the PDF.
 */
public class PasswordEncryptionPostProcessor implements PdfPostProcessor {

    private final String userPassword;
    private final String ownerPassword;

    public PasswordEncryptionPostProcessor(String userPassword, String ownerPassword) {
        this.userPassword = userPassword == null ? "" : userPassword;
        this.ownerPassword = ownerPassword == null ? this.userPassword : ownerPassword;
    }

    @Override
    public byte[] process(byte[] pdf) {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            AccessPermission ap = new AccessPermission();
            ap.setCanModify(false);
            ap.setCanModifyAnnotations(false);
            ap.setCanExtractContent(true);
            ap.setCanPrint(true);
            StandardProtectionPolicy policy =
                    new StandardProtectionPolicy(ownerPassword, userPassword, ap);
            policy.setEncryptionKeyLength(256);
            doc.protect(policy);

            ByteArrayOutputStream baos = new ByteArrayOutputStream(pdf.length + 1024);
            doc.save(baos);
            return baos.toByteArray();
        } catch (IOException e) {
            throw new ReportGenerationException("PDF encryption failed: " + e.getMessage(), e);
        }
    }
}
