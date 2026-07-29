package com.docstruct.parser;

import java.io.IOException;
import java.util.Map;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import com.docstruct.domain.DocumentFormat;
import com.docstruct.exception.ParseException;

@Component
public class PdfParser implements DocumentParser {

    @Override
    public DocumentFormat format() {
        return DocumentFormat.PDF;
    }

    @Override
    public ParseResult parse(byte[] content, String mimeType) {
        try (PDDocument document = Loader.loadPDF(content)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String text = stripper.getText(document).trim();

            if (text.isEmpty()) {
                throw new ParseException(
                        "No text could be extracted from the PDF",
                        "The PDF may be a scanned image without embedded text. Try uploading it as an image instead.");
            }

            return ParseResult.ofText(text, DocumentFormat.PDF, Map.of(
                    "pageCount", document.getNumberOfPages(),
                    "charCount", text.length()));
        } catch (IOException e) {
            throw new ParseException("Failed to read PDF: " + e.getMessage());
        }
    }
}
