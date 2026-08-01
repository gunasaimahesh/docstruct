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
            - "text": free-form text — ONE atomic fact or one short prose block (a summary paragraph, \
            a single skill-category list). Never a multi-attribute record packed into one string.
            - "number": numeric values (quantities, counts, percentages)
            - "date": dates, always normalized to ISO format (YYYY-MM-DD)
            - "currency": monetary amounts as plain numbers WITHOUT symbols or thousands separators
            - "boolean": true/false values
            - "email": email addresses
            - "url": web addresses
            - "entity_array": a nested table of records. Must include an "entitySchema" with its own \
            "name", "description" and "columns". Use this whenever a section has multiple attributes \
            per item OR multiple items — the Knowledge UI renders entity_array as a real table.
            """;

    /**
     * Document-agnostic rules for when to use tables vs scalar fields. Resume/invoice examples
     * are illustrations only — the rule is about shape, not document type.
     */
    private static final String STRUCTURING_RULES = """
            STRUCTURING RULES — these decide table vs field in the UI:
            1. MULTI-ATTRIBUTE RECORD → entity_array. If an item has two or more distinct attributes \
            (school + degree + year + gpa; award + year; title + authors + venue; vendor + amount; \
            drug + dosage + frequency), it is an entity_array with one nested column per attribute. \
            NEVER concatenate those attributes into one text string.
            2. ONE ITEM STILL A TABLE. A single degree, single award, or single publication is still \
            an entity_array with one nested row. Count does not change the shape.
            3. LIST OF ITEMS → entity_array. Multiple jobs, line items, transactions, bullet achievements \
            — one nested row each.
            4. LABELED GROUPS → prefer entity_array with columns like "category" and "items" (or one \
            row per item with "name"), so the UI gets a table. Separate top-level text columns per \
            group are acceptable only when each value is a short atomic list with no other attributes.
            5. TRUE SCALARS STAY TEXT. A person's name, a single email, a phone number, a location, \
            a URL, or one prose summary paragraph — type "text" / "email" / "url". Do not wrap a \
            paragraph in a one-column table. If the document contains a summary / abstract / profile \
            paragraph, it MUST be its own text column — never drop it.
            6. Name entity_array columns after the entity ("Education", "Experience", "Line Items", \
            "Publications") — never suffixes like "Array" or "List".
            7. NEVER TRUNCATE. Copy full values. If a role has multiple bullet achievements, put them \
            in a nested entity_array (one row per bullet) — do not merge them into one shortened \
            paragraph or cut mid-sentence.
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

            Nested fields inside an entity_array use this SAME cell object — each nested field needs \
            its own page, chunk and raw_source. Do NOT put citations only on the parent array cell \
            and leave nested fields as bare values.

            Confidence describes how clearly you could READ the value — it is not a guess at whether \
            the value is correct:
            - "high": written explicitly and unambiguously in the cited chunk.
            - "medium": present, but the label wording differs from the column name, the layout is \
            ambiguous, or the text is partially unclear.
            - "low": you are not sure you read it correctly. "low" is never permission to invent a \
            value — if the value is absent, return null instead.
            """;

    /**
     * The reading layer on top of the extracted data. The UI holds no per-document-type
     * templates, so whatever grouping comes back here is the grouping that gets rendered —
     * which is why it has to be read off the document in front of the model rather than
     * recalled from what documents of that kind usually look like.
     */
    private static final String DOCUMENT_SEMANTICS = """
            DOCUMENT SEMANTICS — how a person would read this document:
            - "document_type_info.name": what a reader would call this document, in Title Case \
            ("Income Tax Return", "Commercial Invoice", "Software Engineer Resume", "Discharge Summary") \
            — not snake_case, and not a generic word like "Document".
            - "document_type_info.category": the family the document belongs to ("Financial", "Legal", \
            "Medical", "Employment", "Academic", "Identity", ...). There is no fixed list; name the family \
            that actually fits.
            - "knowledge_sections": the sections THIS document is organized around, in the order a reader \
            meets them. Title them the way the document titles them ("Taxpayer Information", \
            "Payment Summary", "Lab Results") — never "Group 1", "Other" or "Fields". Write titles in \
            Title Case even where the document shouts them in capitals.
            - Read the sections off the document, not off a template for its type. An invoice tends toward \
            Vendor / Customer / Line Items / Payment Summary and a resume toward Contact / Experience / \
            Education / Skills, but those are illustrations only.
            - Every string in a section's "fields" MUST be an exact top-level column name. Never invent a \
            field name and never reach into a nested entitySchema — an entity_array column is listed by its \
            own column name, inside the section it belongs to.
            - COVERAGE IS MANDATORY: every top-level schema column MUST appear in exactly one section. \
            Do not skip summary, skills, contact, or any other column because another section looked more \
            interesting. Omitting a column hides it from the reader.
            - Prefer grouping related columns under one document-named section (e.g. several skill \
            categories under "Skills") rather than one section per column — but never at the cost of \
            leaving a column out.
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

    /**
     * Query UX semantics inferred once at extraction. Values for dropdowns are NEVER listed
     * here — the database is the source of truth after ingestion.
     */
    private static final String QUERY_HINT_RULES = """
            QUERY HINTS — for every scalar column (top-level AND nested inside entitySchema.columns), \
            include a "queryHint" object that describes how a user would query this field. Infer \
            semantics only; never invent an enum of possible values (those live in the database and \
            change as documents arrive). Nested attributes like company, degree, title, venue are \
            first-class filter targets — they MUST carry queryHint too.

            Allowed "role" values (pick the closest):
            status, person_name, company, organization, money, currency, percentage, date, phone, \
            email, url, country, city, identifier, description, boolean, number

            Rules:
            - "filterable": true for facts a user would filter or search on — including nested \
            company/title/degree/venue and free-text skills/summary (those are searchable). Prefer \
            true. Use false only when a field truly has no query value.
            - "sortable": true for numbers, money, dates, identifiers, names; false for long prose.
            - "groupable": true for low-cardinality categories users pick from a list — status, \
            country, city, currency, company, organization, and similar enums (degree when few \
            distinct values). false for free text and high-cardinality identifiers.
            - "role": company/organization for employers; person_name for people; description for \
            prose paragraphs and skill lists; identifier for IDs/codes.
            - "unit": optional unit/currency code when obvious (e.g. "USD", "%").
            - "example": optional short example drawn from THIS document (e.g. "Amazon"), not a list.
            - Do NOT include a "values" array. Ever.
            - Top-level entity_array columns themselves omit queryHint (they are tables). Their \
            nested scalar columns MUST still include queryHint.
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
                Identity and header facts that ARE written in the CONTEXT — a person or organization \
                name, email address, phone/mobile number, web URL (including profile links), and \
                location/address — MUST be first-class top-level columns, typed "email" or "url" when \
                that fits. Do not bury those facts only inside a free-text summary, and do not skip \
                them because repeating sections (experience, line items, …) look more interesting.

                """ + STRUCTURING_RULES + """

                3. Extract every data record into rows that follow that schema. A document that describes \
                one thing (a single invoice) produces ONE row; a document that lists many things \
                (a CSV, a bank statement) produces one row PER record.
                4. Describe the document to a reader: name its type, name its family, and group the \
                columns you designed into the sections the document is organized around.

                """ + COLUMN_TYPES + """

                """ + CELL_FORMAT + """

                """ + NORMALIZATION_RULES + """

                """ + QUERY_HINT_RULES + """

                """ + DOCUMENT_SEMANTICS + """

                Respond with ONLY a valid JSON object, no prose, matching exactly this structure:
                {
                  "document_type": "<snake_case document type>",
                  "document_type_info": {
                    "name": "<human-readable document name>",
                    "category": "<the document family>"
                  },
                  "knowledge_sections": [
                    {
                      "title": "<section title, as the document names it>",
                      "description": "<one line on what this section covers>",
                      "fields": ["<exact schema column name>", "..."]
                    }
                  ],
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
                        "queryHint": {
                          "filterable": true,
                          "sortable": true,
                          "groupable": false,
                          "role": "<one of the allowed roles>",
                          "unit": "<optional unit>",
                          "example": "<optional short example from this document>"
                        },
                        "entitySchema": {
                          "name": "...",
                          "description": "...",
                          "columns": [
                            {
                              "name": "<Nested Attribute>",
                              "type": "<allowed type>",
                              "description": "<...>",
                              "required": true,
                              "queryHint": {
                                "filterable": true,
                                "sortable": true,
                                "groupable": true,
                                "role": "<role>",
                                "example": "<optional example>"
                              }
                            }
                          ]
                        }
                      }
                    ],
                    "confidence": "high" | "medium" | "low"
                  },
                  "rows": [ { "<Column Name>": { <cell object> } } ],
                  "warnings": ["<anything ambiguous, malformed, unreadable or skipped>"]
                }
                Only include "entitySchema" on columns of type "entity_array".
                Omit "queryHint" on the entity_array column itself; include queryHint on every nested \
                scalar column. Never include a "values" list in queryHint.
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

                """ + QUERY_HINT_RULES + """

                """ + DOCUMENT_SEMANTICS + """

                Rules:
                - Row keys MUST match the existing schema column names exactly.
                - If a schema field is absent from this document, use value null with null page and \
                chunk. Do NOT carry a value over from what a document of this type usually contains.
                - If the document contains clearly useful data that does NOT fit any existing column, \
                report it in "new_columns" (do not silently drop it). Only propose a new column when the \
                data is genuinely valuable and recurring — not for one-off noise. Identity facts that \
                appear in the CONTEXT but are missing from the schema (name, email, phone, URL, \
                location) are always worth proposing. Every new_column MUST include a queryHint.
                - Describe THIS document's own type and sections, grouping the columns of the existing \
                schema above. Do not carry over a grouping from another document.

                Respond with ONLY a valid JSON object matching exactly this structure:
                {
                  "rows": [ { "<Column Name>": { <cell object> } } ],
                  "new_columns": [
                    {
                      "name": "<Column Name>",
                      "type": "<allowed type>",
                      "description": "<what it holds>",
                      "queryHint": {
                        "filterable": true,
                        "sortable": true,
                        "groupable": false,
                        "role": "<one of the allowed roles>",
                        "unit": "<optional unit>",
                        "example": "<optional short example from this document>"
                      }
                    }
                  ],
                  "document_type_info": {
                    "name": "<human-readable document name>",
                    "category": "<the document family>"
                  },
                  "knowledge_sections": [
                    {
                      "title": "<section title, as the document names it>",
                      "description": "<one line on what this section covers>",
                      "fields": ["<exact existing schema column name>", "..."]
                    }
                  ],
                  "warnings": ["<anything ambiguous, malformed, unreadable or skipped>"]
                }
                Never include a "values" list in queryHint.

                CONTEXT:
                ---
                """ + documentContext + """

                ---""";
    }

    /**
     * Second-pass repair: turn flat multi-attribute text columns into entity_array tables.
     * Document-agnostic — operates on column shape, not resume/invoice vocabulary.
     */
    public static String restructureFlatRecords(String documentContext, String flatFieldsJson) {
        return """
                You are DocStruct's structure repair step. Some columns were wrongly extracted as a \
                single text string even though they describe records with multiple attributes (or a \
                list of items). Split them into entity_array tables so the UI can show real columns.

                """ + GROUNDING_RULES + """

                """ + STRUCTURING_RULES + """

                """ + CELL_FORMAT + """

                FLAT FIELDS TO CONSIDER (JSON array of { "column", "value", "page", "chunk", "raw_source" }):
                """ + flatFieldsJson + """

                For each field, decide:
                - KEEP as text when the value is one atomic fact or one short prose block.
                - RESTRUCTURE into an entity_array when it packs multiple attributes of one entity, \
                or lists multiple items. Nested columns must be the real attributes (never a single \
                "text" or "value" catch-all column).

                Use ONLY facts present in the field's value and the CONTEXT. Do not invent attributes.

                Respond with ONLY JSON:
                {
                  "restructured": {
                    "<column name>": {
                      "entitySchema": {
                        "name": "<entity name>",
                        "description": "<what each row is>",
                        "columns": [
                          {"name": "<Attribute>", "type": "text", "description": "<...>", "required": true}
                        ]
                      },
                      "rows": [
                        { "<Attribute>": { "value": "...", "page": 1, "chunk": 1, "raw_source": "...", "confidence": "high", "importance": "medium", "searchable": true } }
                      ]
                    }
                  }
                }
                Omit columns you keep as text. An empty "restructured" object is valid.

                CONTEXT:
                ---
                """ + documentContext + """

                ---""";
    }

    /**
     * Prompt to convert a natural-language question into a PostgreSQL SELECT query.
     *
     * <p>The model decides first whether the input is a question about this data at
     * all, because a best-effort query for "hi" is a confident-looking answer to a
     * question nobody asked. The test is intent, never query shape: "show me
     * everything" is a real request and must still produce SQL.
     *
     * <p>The examples are load-bearing. A single instruction not to answer greetings
     * drifts once the schema block grows; worked pairs in both directions hold.
     */
    public static String queryToSql(String query, String tablesSchema) {
        return """
                You are an expert PostgreSQL query generator. Decide whether the user's input is a \
                question about the data in the tables below, and if it is, convert it into a single \
                PostgreSQL SELECT statement.

                TABLES:
                %s

                STEP 1 — is this input asking something about the data in those tables?
                - Greetings, thanks, small talk, and questions about you or this tool are NOT.
                - Anything a user could plausibly be asking of this data IS — including vague, \
                open-ended and very broad requests.
                - Breadth is never a reason to refuse. "Show me everything" is a real request.
                - A question whose answer happens to be absent from these tables is still a real \
                question: write the query and let the empty result say so.

                Respond with ONLY a valid JSON object — no prose, no markdown fences.

                If it is NOT a question about the data:
                {
                  "answerable": false,
                  "reason": "<one short sentence, addressed to the user, saying why>"
                }

                If it IS:
                {
                  "answerable": true,
                  "sql": "<the SELECT statement>",
                  "explanation": "<one sentence describing what the query does>"
                }

                STEP 2 — rules for the SQL:
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

                EXAMPLES (for a collection whose table is "data_abc123")

                Input: hi
                {"answerable": false, "reason": "That looks like a greeting rather than a question about your data."}

                Input: thanks!
                {"answerable": false, "reason": "That looks like a greeting rather than a question about your data."}

                Input: how are you
                {"answerable": false, "reason": "That is about me rather than about the data in this collection."}

                Input: what can you do
                {"answerable": false, "reason": "That is about this tool rather than about the data in this collection."}

                Input: anything unusual in here
                {"answerable": true, "sql": "SELECT * FROM \\"data_abc123\\" LIMIT 100", "explanation": "Returns rows so unusual values can be inspected."}

                Input: show me everything
                {"answerable": true, "sql": "SELECT * FROM \\"data_abc123\\"", "explanation": "Returns every row in the collection."}

                Input: what's in this collection
                {"answerable": true, "sql": "SELECT * FROM \\"data_abc123\\"", "explanation": "Returns every row in the collection."}

                QUESTION: %s""".formatted(tablesSchema, query);
    }

    /**
     * Phrase an already-computed answer. The model must not invent or recompute
     * any number — the headline and coverage are authoritative.
     */
    public static String phraseAnswer(String query, String factsJson) {
        return """
                A user asked: "%s"

                The system already computed these facts (authoritative — do not change them):
                %s

                Rewrite the headline as one or two plain-English sentences that answer the question.
                You may incorporate caveats briefly when they affect trust in the answer.

                Rules:
                - Use ONLY the facts above. Never invent, round, convert, or recompute any number or name.
                - If the headline already answers clearly, you may return it nearly verbatim.
                - Do not mention SQL, schemas, or internal field names.

                Respond with the phrased answer only — no JSON, no markdown.""".formatted(query, factsJson);
    }
}
