package com.docstruct.llm;

/**
 * Prompt templates for the extraction and query workflows.
 * Each prompt pins down the exact JSON contract that the response
 * mappers ({@link ExtractionResponseMapper}, QueryService) consume.
 */
public final class PromptTemplates {

    private PromptTemplates() {
    }

    private static final String COLUMN_TYPES = """
            Allowed column types:
            - "text": free-form text
            - "number": numeric values (quantities, counts, percentages)
            - "date": dates, always normalized to ISO format (YYYY-MM-DD)
            - "currency": monetary amounts as plain numbers WITHOUT symbols or thousands separators
            - "boolean": true/false values
            - "email": email addresses
            - "url": web addresses
            - "entity_array": a repeating nested entity (e.g. line items on an invoice). \
            Must include an "entitySchema" with its own "name", "description" and "columns".
            """;

    private static final String CELL_FORMAT = """
            Every cell in "rows" must be an object of the form:
            {
              "value": <the extracted value, or null if absent; for entity_array columns an array of nested row objects in the same cell format>,
              "confidence": "high" | "medium" | "low",
              "importance": "high" | "medium" | "low",
              "searchable": true | false,
              "raw_source": "<the exact text from the document this value came from, when available>"
            }

            Confidence rules:
            - "high": the value is clearly and unambiguously stated in the document.
            - "medium": a reasonable inference (label wording differs, value partially obscured, or derived).
            - "low": a guess; the document is unclear or the value may be wrong.
            """;

    /** Prompt for the first document in a collection: infer schema AND extract data. */
    public static String schemaInference(String documentText) {
        return """
                You are DocStruct, an expert document data extraction engine. You turn messy \
                documents into structured, queryable data.

                Analyze the document below, then:
                1. Identify what kind of document it is (e.g. "invoice", "receipt", "bank_statement", "resume").
                2. Design a flat, tabular schema that captures ALL useful data in the document. \
                Use snake_case-friendly, human-readable column names. Repeating sub-records \
                (line items, transactions, work experiences, achievements, awards, certifications, \
                publications, skills) must be modeled as an "entity_array" column with a nested \
                entitySchema — never flattened or concatenated into text. \
                CRITICAL: if a value would contain several distinct items joined together \
                (e.g. multiple achievements in one string separated by periods or bullets), that is \
                WRONG — split them into an entity_array with one nested row per item instead. \
                A text column must hold exactly ONE fact. \
                Name entity_array columns after the entity itself (e.g. "Education", "Experience", \
                "Achievements", "Line Items") — never append structural words like "Array" or "List".
                3. Extract every data record into rows that follow that schema. A document that describes \
                one thing (a single invoice) produces ONE row; a document that lists many things \
                (a CSV, a bank statement) produces one row PER record.

                """ + COLUMN_TYPES + """

                """ + CELL_FORMAT + """

                Normalization rules:
                - Dates: ISO format (YYYY-MM-DD).
                - Currency: plain numbers, no symbols or thousands separators (e.g. 1234.5).
                - Booleans: true/false.
                - Do NOT invent values that are not present in the document; use null with "low" confidence instead.

                Respond with ONLY a valid JSON object, no prose, matching exactly this structure:
                {
                  "document_type": "<snake_case document type>",
                  "document_analysis": {
                    "purpose": "<what this document is for>",
                    "owner": "<who issued/owns the document, if identifiable>",
                    "audience": "<who the document is addressed to, if identifiable>",
                    "useful_data_identified": "<one sentence on what data was found>",
                    "detected_sections": ["<section name>", "..."],
                    "ai_summary": "<2-3 sentence plain-English summary of the document's content and key facts>"
                  },
                  "schema": {
                    "columns": [
                      {
                        "name": "<Column Name>",
                        "type": "<one of the allowed types>",
                        "description": "<what this column holds>",
                        "required": true,
                        "entitySchema": { "name": "...", "description": "...", "columns": [ ... ] }
                      }
                    ],
                    "confidence": "high" | "medium" | "low"
                  },
                  "rows": [ { "<Column Name>": { <cell object> } } ],
                  "warnings": ["<anything ambiguous, malformed, or skipped>"]
                }
                Only include "entitySchema" on columns of type "entity_array".
                Row keys MUST match the schema column names exactly.

                DOCUMENT:
                ---
                """ + documentText + """

                ---""";
    }

    /** Prompt for subsequent documents: extract against an existing schema, detecting new columns. */
    public static String schemaMatching(String documentText, String schemaJson, String documentType) {
        return """
                You are DocStruct, an expert document data extraction engine.

                A collection of "%s" documents already exists with the schema below. Extract the data \
                from the new document into rows that follow this EXISTING schema.

                EXISTING SCHEMA:
                %s

                """.formatted(documentType, schemaJson) + COLUMN_TYPES + """

                """ + CELL_FORMAT + """

                Rules:
                - Row keys MUST match the existing schema column names exactly.
                - If a schema field is absent from the document, use value null with "low" confidence.
                - If the document contains clearly useful data that does NOT fit any existing column, \
                report it in "new_columns" (do not silently drop it). Only propose a new column when the \
                data is genuinely valuable and recurring — not for one-off noise.
                - Dates in ISO format, currency as plain numbers, booleans as true/false.

                Respond with ONLY a valid JSON object matching exactly this structure:
                {
                  "rows": [ { "<Column Name>": { <cell object> } } ],
                  "new_columns": [ { "name": "<Column Name>", "type": "<allowed type>", "description": "<what it holds>" } ],
                  "warnings": ["<anything ambiguous, malformed, or skipped>"]
                }

                DOCUMENT:
                ---
                """ + documentText + """

                ---""";
    }

    /** Prompt to convert a natural-language question into a PostgreSQL SELECT query. */
    public static String queryToSql(String query, String tablesSchema) {
        return """
                You are an expert PostgreSQL query generator. Convert the user's natural language \
                question into a single PostgreSQL SELECT statement over the tables described below.

                TABLES:
                %s

                Rules:
                - Generate exactly ONE SELECT statement. Never generate INSERT, UPDATE, DELETE, DROP, \
                ALTER, CREATE, TRUNCATE or any other statement type.
                - Use the exact table names given above, wrapped in double quotes \
                (e.g. SELECT ... FROM "data_abc123").
                - Wrap every column name in double quotes.
                - Columns starting with an underscore (_row_id, _document_id, _confidence, ...) are \
                internal; do not select them unless needed for a join between a parent table's _row_id \
                and a child table's _parent_row_id.
                - Use PostgreSQL syntax: ILIKE for case-insensitive text matching, standard aggregate \
                functions, LIMIT for row caps.
                - Text columns may contain nulls; handle them gracefully.
                - If the question cannot be answered from these tables, still return your best-effort query.

                Respond with ONLY a valid JSON object:
                {
                  "sql": "<the SELECT statement>",
                  "explanation": "<one sentence describing what the query does>"
                }

                QUESTION: %s""".formatted(tablesSchema, query);
    }

    /** Prompt to summarize query results in one or two sentences. */
    public static String resultsSummary(String query, String sql, int rowCount, String rowsJson) {
        return """
                A user asked: "%s"

                The generated SQL was: %s
                It returned %d row(s). Here are the results (possibly truncated):
                %s

                Write a 1-2 sentence plain-English summary of these results that directly answers \
                the user's question. Mention concrete numbers where relevant. Respond with the \
                summary text only — no JSON, no markdown.""".formatted(query, sql, rowCount, rowsJson);
    }
}
