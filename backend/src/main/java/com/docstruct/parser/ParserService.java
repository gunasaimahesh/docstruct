package com.docstruct.parser;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.docstruct.domain.DocumentFormat;
import com.docstruct.exception.DocStructException;
import com.docstruct.exception.ParseException;
import com.docstruct.exception.UnsupportedFormatException;

/** Detects a document's format and routes it to the right parser. */
@Service
public class ParserService {

    private static final Map<String, DocumentFormat> MIME_TO_FORMAT = Map.of(
            "application/pdf", DocumentFormat.PDF,
            "text/csv", DocumentFormat.CSV,
            "text/plain", DocumentFormat.TEXT,
            "text/tab-separated-values", DocumentFormat.CSV,
            "image/png", DocumentFormat.IMAGE,
            "image/jpeg", DocumentFormat.IMAGE,
            "image/jpg", DocumentFormat.IMAGE,
            "image/webp", DocumentFormat.IMAGE,
            "image/tiff", DocumentFormat.IMAGE);

    private static final Map<String, DocumentFormat> EXT_TO_FORMAT = Map.ofEntries(
            Map.entry("pdf", DocumentFormat.PDF),
            Map.entry("csv", DocumentFormat.CSV),
            Map.entry("tsv", DocumentFormat.CSV),
            Map.entry("txt", DocumentFormat.TEXT),
            Map.entry("text", DocumentFormat.TEXT),
            Map.entry("md", DocumentFormat.TEXT),
            Map.entry("log", DocumentFormat.TEXT),
            Map.entry("png", DocumentFormat.IMAGE),
            Map.entry("jpg", DocumentFormat.IMAGE),
            Map.entry("jpeg", DocumentFormat.IMAGE),
            Map.entry("webp", DocumentFormat.IMAGE),
            Map.entry("tiff", DocumentFormat.IMAGE),
            Map.entry("tif", DocumentFormat.IMAGE));

    private final Map<DocumentFormat, DocumentParser> parsers = new EnumMap<>(DocumentFormat.class);

    public ParserService(List<DocumentParser> parserBeans) {
        parserBeans.forEach(p -> parsers.put(p.format(), p));
    }

    public DocumentFormat detectFormat(String mimeType, String filename) {
        if (mimeType != null) {
            DocumentFormat fromMime = MIME_TO_FORMAT.get(mimeType.toLowerCase(Locale.ROOT));
            if (fromMime != null) {
                return fromMime;
            }
        }
        String ext = "";
        if (filename != null && filename.contains(".")) {
            ext = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        }
        DocumentFormat fromExt = EXT_TO_FORMAT.get(ext);
        if (fromExt != null) {
            return fromExt;
        }
        throw new UnsupportedFormatException(
                mimeType != null && !mimeType.isBlank() ? mimeType : (ext.isEmpty() ? "unknown" : ext));
    }

    public ParseResult parse(byte[] content, String filename, String mimeType) {
        DocumentFormat format = detectFormat(mimeType, filename);
        try {
            return parsers.get(format).parse(content, mimeType);
        } catch (DocStructException e) {
            throw e;
        } catch (Exception e) {
            throw new ParseException(
                    "Failed to parse %s: %s".formatted(filename, e.getMessage()),
                    "Format: %s, Size: %d bytes".formatted(format.toJson(), content.length));
        }
    }
}
