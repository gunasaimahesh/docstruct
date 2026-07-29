package com.docstruct.parser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
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
            // Extracted page by page rather than in one pass: page numbers are the
            // unit users verify a citation against, so the boundaries must survive.
            List<String> pages = extractPages(document);
            String text = String.join("\n\n", pages).trim();

            if (text.isEmpty()) {
                throw new ParseException(
                        "No text could be extracted from the PDF",
                        "The PDF may be a scanned image without embedded text. Try uploading it as an image instead.");
            }

            return ParseResult.ofText(text, DocumentFormat.PDF, Chunker.chunkPages(pages), Map.of(
                    "pageCount", document.getNumberOfPages(),
                    "charCount", text.length()));
        } catch (IOException e) {
            throw new ParseException("Failed to read PDF: " + e.getMessage());
        }
    }

    private List<String> extractPages(PDDocument document) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setSortByPosition(true);

        List<String> pages = new ArrayList<>();
        for (int page = 1; page <= document.getNumberOfPages(); page++) {
            stripper.setStartPage(page);
            stripper.setEndPage(page);
            pages.add(stripper.getText(document).strip());
        }
        return pages;
    }
}
