package com.docstruct.llm;

/**
 * Prompt templates for the extraction and query workflows.
 * Each prompt pins down the exact JSON contract that the response
 * mappers ({@link ExtractionResponseMapper}, QueryService) consume.
 */
public final class PromptTemplates {

    private PromptTemplates() {
    }

    /**
     * The anti-hallucination contract. Stated first, stated as overriding, and
     * repeated in the cell format below — a single buried "do not invent values"
     * line does not survive a long prompt.
     */
    private static final String GROUNDING_RULES = """
            GROUNDING RULES — these override every other instruction:
            1. The CONTEXT blocks are the ONLY permitted source of information. Ignore everything you \
            know about invoices, companies, people or documents in general.
            2. Never infer, estimate, calculate, complete or "tidy up" a value. If a value is not \
            written in the CONTEXT, it does not exist.
            3. If a field is not present in the CONTEXT, return "value": null with "page": null and \
            "chunk": null. A null is a CORRECT answer. A plausible guess is a failure.
            4. Copy values exactly as written — spelling, casing, punctuation and internal spacing. \
            The only permitted changes are the normalizations listed below.
            5. Every non-null value MUST cite the chunk it was read from AND include "raw_source": a \
            verbatim, character-for-character substring of that chunk. Never paraphrase, retype, \
            summarize or reformat raw_source.
            6. Citations are checked against the document after you answer. A citation that does not \
            verify lowers the field's confidence, so cite the chunk you actually read the value in.
            7. If the CONTEXT states the document is an image with no text layer, read it visually, \
            use "page": 1 and "chunk": null, and set raw_source to the exact text visible in the image.
            8. Output valid JSON only — no prose, no markdown fences, no comments.
            """;

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
              "value": <the exact value from the CONTEXT, or null if absent; for entity_array columns \
            an array of nested row objects in this same cell format>,
              "page": <page number of the chunk this value was read from, or null>,
              "chunk": <chunk number this value was read from, or null>,
              "raw_source": "<verbatim substring of that chunk containing the value; null when value is null>",
              "confidence": "high" | "medium" | "low",
              "importance": "high" | "medium" | "low",
              "searchable": true | false
            }

            Confidence describes how clearly you could READ the value — it is not a guess at whether \
            the value is correct:
            - "high": written explicitly and unambiguously in the cited chunk.
            - "medium": present, but the label wording differs from the column name, the layout is \
            ambiguous, or the text is partially unclear.
            - "low": you are not sure you read it correctly. "low" is never permission to invent a \
            value — if the value is absent, return null instead.
            """;

    private static final String NORMALIZATION_RULES = """
            Normalization rules (the ONLY permitted transformations, and only when the source is \
            unambiguous — otherwise return null):
            - Dates: ISO format (YYYY-MM-DD). Do not fill in a missing day, month or year.
            - Currency: plain numbers, no symbols or thousands separators (e.g. 1234.5). Do not \
            convert between currencies.
            - Booleans: true/false.
            - Everything else: character-for-character as written in the document.
            """;

    /** Prompt for the first document in a collection: infer schema AND extract data. */
    public static String schemaInference(String documentContext) {
        return """
                You are DocStruct, an expert document data extraction engine. You turn messy \
                documents into structured, queryable data. You are trusted because you never \
                report a value the document does not contain.

                """ + GROUNDING_RULES + """

                Analyze the CONTEXT below, then:
                1. Identify what kind of document it is (e.g. "invoice", "receipt", "bank_statement", "resume").
                2. Design a flat, tabular schema that captures ALL useful data actually present in the \
                document. Use snake_case-friendly, human-readable column names. Do NOT add columns for \
                fields you would expect this document type to have but which are absent here. \
                Repeating sub-records (line items, transactions, work experiences, achievements, awards, \
                certifications, publications, skills) must be modeled as an "entity_array" column with a \
                nested entitySchema — never flattened or concatenated into text. \
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

                """ + NORMALIZATION_RULES + """

                Respond with ONLY a valid JSON object, no prose, matching exactly this structure:
                {
                  "document_type": "<snake_case document type>",
                  "document_analysis": {
                    "purpose": "<what this document is for>",
                    "owner": "<who issued/owns the document, if identifiable>",
                    "audience": "<who the document is addressed to, if identifiable>",
                    "useful_data_identified": "<one sentence on what data was found>",
                    "detected_sections": ["<section name>", "..."],
                    "ai_summary": "<2-3 sentence summary built ONLY from facts present in the CONTEXT>"
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
                  "warnings": ["<anything ambiguous, malformed, unreadable or skipped>"]
                }
                Only include "entitySchema" on columns of type "entity_array".
                Row keys MUST match the schema column names exactly.

                CONTEXT:
                ---
                """ + documentContext + """

                ---""";
    }

    /** Prompt for subsequent documents: extract against an existing schema, detecting new columns. */
    public static String schemaMatching(String documentContext, String schemaJson, String documentType) {
        return """
                You are DocStruct, an expert document data extraction engine. You are trusted because \
                you never report a value the document does not contain.

                """ + GROUNDING_RULES + """

                A collection of "%s" documents already exists with the schema below. Extract the data \
                from the new document into rows that follow this EXISTING schema.

                EXISTING SCHEMA:
                %s

                """.formatted(documentType, schemaJson) + COLUMN_TYPES + """

                """ + CELL_FORMAT + """

                """ + NORMALIZATION_RULES + """

                Rules:
                - Row keys MUST match the existing schema column names exactly.
                - If a schema field is absent from this document, use value null with null page and \
                chunk. Do NOT carry a value over from what a document of this type usually contains.
                - If the document contains clearly useful data that does NOT fit any existing column, \
                report it in "new_columns" (do not silently drop it). Only propose a new column when the \
                data is genuinely valuable and recurring — not for one-off noise.

                Respond with ONLY a valid JSON object matching exactly this structure:
                {
                  "rows": [ { "<Column Name>": { <cell object> } } ],
                  "new_columns": [ { "name": "<Column Name>", "type": "<allowed type>", "description": "<what it holds>" } ],
                  "warnings": ["<anything ambiguous, malformed, unreadable or skipped>"]
                }

                CONTEXT:
                ---
                """ + documentContext + """

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
                the user's question.

                Rules:
                - Use ONLY the rows above. Never add context, explanations or figures that are not in them.
                - Quote numbers and names exactly as they appear; do not round, convert or recompute.
                - If the rows do not answer the question, say so plainly instead of filling the gap.
                - If the results were truncated, describe only what is shown.

                Respond with the summary text only — no JSON, no markdown.""".formatted(query, sql, rowCount, rowsJson);
    }
}
