package com.docstruct.parser;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

import com.docstruct.domain.DocumentFormat;
import com.docstruct.exception.ParseException;

@Component
public class CsvParser implements DocumentParser {

    @Override
    public DocumentFormat format() {
        return DocumentFormat.CSV;
    }

    @Override
    public ParseResult parse(byte[] content, String mimeType) {
        String raw = new String(content, StandardCharsets.UTF_8);
        char delimiter = mimeType != null && mimeType.contains("tab-separated") ? '\t' : ',';

        // Validate the CSV structure and count rows; the LLM receives the raw
        // text, which it reads perfectly well in its original form.
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setDelimiter(delimiter)
                .setIgnoreEmptyLines(true)
                .get();

        int rowCount = 0;
        int columnCount = 0;
        try (CSVParser parser = CSVParser.parse(new StringReader(raw), format)) {
            for (CSVRecord record : parser) {
                if (rowCount == 0) {
                    columnCount = record.size();
                }
                rowCount++;
            }
        } catch (IOException e) {
            throw new ParseException("Failed to parse CSV: " + e.getMessage());
        }

        if (rowCount == 0) {
            throw new ParseException("CSV file contains no rows");
        }

        // Chunked with the header repeated in each chunk, so a chunk of data rows
        // stays self-describing and the model never has to guess column meanings.
        String trimmed = raw.trim();
        return ParseResult.ofText(trimmed, DocumentFormat.CSV, Chunker.chunkDelimited(trimmed), Map.of(
                "rowCount", rowCount,
                "columnCount", columnCount));
    }
}
