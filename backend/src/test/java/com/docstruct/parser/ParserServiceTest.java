package com.docstruct.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.docstruct.domain.DocumentFormat;
import com.docstruct.exception.ParseException;
import com.docstruct.exception.UnsupportedFormatException;

class ParserServiceTest {

    private final ParserService parserService = new ParserService(
            List.of(new PdfParser(), new CsvParser(), new TextParser(), new ImageParser()));

    @Test
    void detectsFormatFromMimeType() {
        assertThat(parserService.detectFormat("application/pdf", "x.bin")).isEqualTo(DocumentFormat.PDF);
        assertThat(parserService.detectFormat("image/png", "x")).isEqualTo(DocumentFormat.IMAGE);
    }

    @Test
    void fallsBackToFileExtension() {
        assertThat(parserService.detectFormat("application/octet-stream", "data.csv"))
                .isEqualTo(DocumentFormat.CSV);
        assertThat(parserService.detectFormat(null, "notes.md")).isEqualTo(DocumentFormat.TEXT);
    }

    @Test
    void rejectsUnknownFormats() {
        assertThatThrownBy(() -> parserService.detectFormat("application/zip", "archive.zip"))
                .isInstanceOf(UnsupportedFormatException.class);
    }

    @Test
    void parsesCsvContent() {
        byte[] csv = "name,amount\nAcme,10\nGlobex,20\n".getBytes(StandardCharsets.UTF_8);
        ParseResult result = parserService.parse(csv, "data.csv", "text/csv");

        assertThat(result.format()).isEqualTo(DocumentFormat.CSV);
        assertThat(result.text()).contains("Acme,10");
        assertThat(result.metadata()).containsEntry("rowCount", 3);
    }

    @Test
    void rejectsEmptyTextFiles() {
        assertThatThrownBy(() -> parserService.parse("   ".getBytes(StandardCharsets.UTF_8), "empty.txt", "text/plain"))
                .isInstanceOf(ParseException.class);
    }

    @Test
    void imagesArePassedThroughAsBase64() {
        byte[] fakeImage = new byte[] {1, 2, 3};
        ParseResult result = parserService.parse(fakeImage, "receipt.png", "image/png");

        assertThat(result.isImage()).isTrue();
        assertThat(result.imageMimeType()).isEqualTo("image/png");
        assertThat(result.imageBase64()).isNotBlank();
        assertThat(result.text()).isEmpty();
    }
}
