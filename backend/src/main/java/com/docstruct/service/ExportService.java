package com.docstruct.service;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.stereotype.Service;

import com.docstruct.domain.CollectionEntity;
import com.docstruct.repository.DynamicTableRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Exports a collection's extracted data as CSV or JSON. */
@Service
public class ExportService {

    private static final int EXPORT_ROW_LIMIT = 100_000;

    private final CollectionService collectionService;
    private final DynamicTableRepository dynamicTableRepository;
    private final ObjectMapper objectMapper;

    public ExportService(CollectionService collectionService,
                         DynamicTableRepository dynamicTableRepository,
                         ObjectMapper objectMapper) {
        this.collectionService = collectionService;
        this.dynamicTableRepository = dynamicTableRepository;
        this.objectMapper = objectMapper;
    }

    public record Export(String content, String contentType, String filename) {
    }

    public Export export(String collectionId, String format) {
        CollectionEntity collection = collectionService.getOrThrow(collectionId);

        List<Map<String, Object>> rows = dynamicTableRepository
                .getRows(collectionId, null, EXPORT_ROW_LIMIT, 0)
                .rows().stream()
                .map(ExportService::stripInternalColumns)
                .toList();

        String safeFilename = collection.getName().replaceAll("[^a-zA-Z0-9_-]", "_");
        if (safeFilename.length() > 50) {
            safeFilename = safeFilename.substring(0, 50);
        }

        if ("json".equalsIgnoreCase(format)) {
            return new Export(toJson(rows), "application/json", safeFilename + ".json");
        }
        return new Export(toCsv(rows), "text/csv", safeFilename + ".csv");
    }

    private String toJson(List<Map<String, Object>> rows) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(rows);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String toCsv(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return "";
        }
        List<String> headers = List.copyOf(rows.get(0).keySet());
        StringWriter writer = new StringWriter();
        try (CSVPrinter printer = new CSVPrinter(writer,
                CSVFormat.DEFAULT.builder().setHeader(headers.toArray(String[]::new)).get())) {
            for (Map<String, Object> row : rows) {
                printer.printRecord(headers.stream().map(h -> {
                    Object value = row.get(h);
                    return value != null ? value : "";
                }).toList());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return writer.toString();
    }

    private static Map<String, Object> stripInternalColumns(Map<String, Object> row) {
        Map<String, Object> clean = new LinkedHashMap<>();
        row.forEach((key, value) -> {
            if (!key.startsWith("_")) {
                clean.put(key, value);
            }
        });
        return clean;
    }
}
