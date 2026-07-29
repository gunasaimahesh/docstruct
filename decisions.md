# decisions.md — DocStruct

A running log of the real calls I made while building DocStruct.

---

## 1. Problem Choice: Option 3 over Options 1 & 2

**Decision:** Build "Turn messy documents into structured, queryable data."

**Alternatives considered:**
- **Option 1 (Learn & Automate):** Would require reliable pattern learning from very few demonstrations. The core challenge — generalizing from user actions to automated procedures — is essentially unsolved in a robust way. In 5 days, I'd end up with a brittle demo that only works for cherry-picked cases. The hard sub-problems (screen recording, DOM instrumentation, action replay) are all infrastructure with little product payoff.
- **Option 2 (Conversational Agent):** Feasible, but high risk of looking like "just another ChatGPT wrapper." The differentiation would have to come from picking a very specific task domain and nailing it, but even then, the evaluator would be comparing it to tools they already use daily.

**Why Option 3 wins:**
- The hard sub-problems are *concrete and bounded*: messy PDF parsing, schema inference from diverse layouts, handling format variation across documents of the "same type."
- Demo-ability is instant: upload a document → see structured data → query it.
- Every company has this pain. This is a real product, not an exercise.
- "Above and beyond" is tangible: confidence scoring, schema evolution, malformed document handling.

**What I cut:** I didn't attempt to combine problems (e.g., a conversational agent that also structures documents). Depth on one thing > breadth across two.

---

## 2. Architecture: Prototype in Next.js, Refactor to a Dedicated Spring Boot Backend

**Decision:** The final architecture is a proper client-server split — a Java 21 / Spring Boot 3 REST backend (`backend/`) and a Next.js frontend (`frontend/`) that talks to it over HTTP.

**How it actually happened:** I first prototyped the whole product as a Next.js monorepo (API routes as the backend, SQLite for storage). That was the right call for day 1–2: one codebase, zero CORS, instant iteration on the product shape. Once the product worked end to end, I refactored the backend into a standalone Spring Boot service — not as a line-by-line translation, but as a redesign into layered architecture (controller → service → repository, DTOs at the boundary, a domain model in the middle, centralized exception handling via `@RestControllerAdvice`).

**Alternatives considered:**
- **Stay on Next.js API routes:** Fastest to ship and deploy. But the interesting engineering in this problem — transactional writes across fixed and dynamic tables, schema evolution, safe dynamic SQL — deserves a real backend with a real transaction manager, and I wanted the submission to reflect how I'd build this as a backend service in production.
- **Express/Fastify backend:** A smaller step from the prototype, but it wouldn't change anything structurally — same runtime, same ORM options. If I'm going to split the system, I want the strong typing, mature transaction semantics, and ecosystem (Spring Data, Bean Validation, PDFBox) of the JVM.

**Tradeoffs accepted:**
- Two deploy targets and CORS/proxying to manage. Mitigated by a Next.js rewrite rule that proxies `/api/*` to the backend, so the frontend code never changed its fetch paths.
- The refactor cost roughly a day that could have gone into features. I judged the architectural depth to be worth more than another feature.

---

## 3. Persistence: PostgreSQL with a Hybrid JPA + JdbcTemplate Model

**Decision:** PostgreSQL, accessed two ways deliberately: Spring Data JPA for the *fixed* metadata (collections, documents — known shape, standard CRUD) and plain `JdbcTemplate` for the *dynamic* per-collection data tables whose columns are invented by the LLM at upload time.

**Alternatives considered:**
- **SQLite (what the prototype used):** Zero setup, but no real concurrent access and awkward to host alongside a JVM service. Postgres also gives me `JSONB` for storing extraction cells (value + confidence + provenance) with query support.
- **Everything in JPA:** Impossible for the dynamic tables — JPA entities are compile-time constructs and the schema here is invented at runtime. Faking it with an EAV (entity-attribute-value) layout or a single "rows" table keyed by JSON would have worked, but then every user query becomes JSON-path gymnastics instead of honest SQL.
- **Everything in JdbcTemplate:** Consistent, but I'd be hand-writing CRUD for collections/documents that JPA gives me for free, plus losing dirty checking and cascade deletes where they genuinely help.
- **A document/vector store:** The query patterns are relational (filter, aggregate, join parent rows to line items). I didn't want to run a second datastore for a 5-day build, and semantic search wasn't the product.

**The interesting sub-problem:** each collection gets a real physical table (`data_<id>`), and each `entity_array` column (invoice line items, resume work experience) gets a child table joined by `_parent_row_id`. LLM-invented names are sanitized through a strict identifier whitelist before ever touching DDL — dynamic SQL where the *schema itself* is untrusted input is the main injection surface in this design, and it's covered by unit tests.

**A bug this surfaced:** JPA batches writes until flush, but the JdbcTemplate inserts that follow reference the document row via foreign key *within the same transaction*. The fix (`saveAndFlush`) is one line; knowing why it's needed is the point of mixing the two access styles consciously.

---

## 4. Schema Inference: AI-First over Rule-Based

**Decision:** Use an LLM to infer the schema from document content, rather than building rule-based parsers per document type.

**Alternatives considered:**
- **Hand-written parsers per document type:** More reliable for known formats (e.g., a specific invoice template), but would take the full 5 days for 2-3 document types and break on any format variation.
- **User-defined schemas (manual):** Ask the user to define columns before uploading. Simpler to build, but terrible UX. The whole point is that the user shouldn't have to think about structure.
- **Hybrid: rules for known formats + AI fallback:** Ideal for production, but in 5 days I'd rather make the AI path excellent than split effort between two approaches.

**Why AI-first:**
- Handles format variation that regex/rules can't (different invoices calling the same field "Total", "Amount Due", "Grand Total").
- Works across document types without any code changes.
- The prompt engineering is the hard part — and it's where I can add the most value quickly.

**What the prompts had to learn the hard way:** smaller models love flattening repeating data ("achievements" on a resume became one concatenated string instead of a table). The schema-inference prompt now explicitly forbids multi-item text values and names the failure mode. Prompt contracts are pinned in one place (`PromptTemplates`) so the response mappers and the prompts can't drift apart silently.

**Tradeoffs accepted:**
- AI can hallucinate or misextract. That's why confidence scoring exists — users can see which extractions are uncertain and correct them inline.
- AI calls add latency. Acceptable for a batch processing tool (not real-time).

---

## 5. LLM Access: Provider-Agnostic OpenAI-Compatible Client

**Decision:** One thin `LlmClient` over the OpenAI-compatible chat-completions protocol, with the provider (base URL, model, key) supplied entirely by configuration — no provider SDKs.

**Alternatives considered:**
- **Official provider SDK (OpenAI/Google):** Nicer types, but locks the whole codebase to one vendor. The chat-completions protocol is the de-facto standard; nearly every provider speaks it.
- **Spring AI:** Purpose-built abstraction, but it's a heavyweight dependency for what is ultimately two endpoints, and I wanted the request/retry/token-budget behavior to be explicit and debuggable.

**Why this paid off (real story):** I started on OpenRouter proxying Gemini 2.5 Flash. Mid-project, the account ran out of credits — OpenRouter rejects requests whose `max_tokens` *reservation* exceeds the remaining balance, which first showed up as opaque 402s and then as truncated-JSON parse failures after I lowered the cap. Because the provider is just three environment variables, I switched to Groq (`llama-3.3-70b-versatile`) in minutes with zero code changes.

**Failure handling that came out of this:** retries with linear backoff for transient errors, but fail-fast on 4xx (a 402 or 400 will fail identically on every retry — retrying just burns time and quota); a configurable `max_tokens` cap; and markdown-fence stripping because models wrap JSON in code fences no matter how firmly you ask them not to.

**Tradeoff accepted:** the current Groq model has no vision support, so image uploads require pointing the config at a vision-capable provider (e.g. Gemini via Google AI Studio). Documented rather than solved — text/PDF/CSV is the core path.

---

## 6. Confidence Scoring: Built-In, Not Bolted-On

**Decision:** Confidence scores (high/medium/low) are a first-class concept throughout the system — from the AI extraction prompt, through the database schema, to the UI.

**Alternatives considered:**
- **No confidence scoring:** Just show the extracted data and let users figure out what's wrong. This is what most document extraction tools do, and it's terrible. You stare at a table and have no idea if "Vendor: Acme" is right or the AI made it up.
- **Binary confidence (certain/uncertain):** Simpler, but loses the "it's probably right but double-check" middle ground which is the most useful category.

**Why three levels:**
- **High:** Clear, unambiguous extraction. No review needed.
- **Medium:** Reasonable inference, but the field name was slightly different or the value was partially obscured. Worth a quick glance.
- **Low:** The AI is guessing. Review required.

Every cell also carries `raw_source` — the exact document text the value came from — so a user can audit any extraction. This is the hard sub-problem I went deep on. Most people would skip confidence scoring or add it as an afterthought. I made it structural.

---

## 7. Schema Evolution: Dynamic Columns over Rigid Schemas

**Decision:** When a new document is added to a collection and it has fields the existing schema doesn't cover, the system adds new columns rather than dropping the data.

**Alternatives considered:**
- **Strict schema matching:** Reject documents that don't match the schema. Safe, but frustrating — real-world documents always have variation.
- **Schema-per-document:** Each document gets its own schema. No consistency across a collection. Makes querying across documents impossible.
- **Append-only columns:** Add new columns when needed, never remove. This is what I chose — it's the least surprising behavior and mirrors how real databases evolve.

**Implementation:** the extraction prompt for follow-up documents reports fields that don't fit the existing schema as `new_columns` instead of silently dropping them; the backend then runs `ALTER TABLE ... ADD COLUMN` on the collection's data table and merges the column into the stored schema. Old rows get NULL for new columns. The prompt explicitly discourages proposing columns for one-off noise.

---

## 8. Natural Language Queries: LLM-to-SQL over Custom Query Language

**Decision:** Users query data in plain English, which gets converted to a PostgreSQL SELECT by the LLM and executed against the collection's real tables.

**Alternatives considered:**
- **Custom query builder UI:** Dropdowns for column, operator, value. Works for simple filters, but can't handle "top 5 vendors by total spend" or "average invoice amount per month."
- **Direct SQL input:** Powerful but intimidating. The target user is an ops analyst, not a developer.
- **Both (NL + filter UI):** Ideal for production. I built the NL path first because it's harder and more impressive.

**Safety:** generated SQL is validated before execution — single SELECT statement only, dangerous keywords (INSERT/UPDATE/DELETE/DROP/ALTER/...) rejected, and it runs against tables whose names the backend supplies, not the model. The generated SQL is shown to the user for transparency. Because collection data lives in real typed tables (decision #3), the generated SQL is ordinary SQL — no JSON-path acrobatics.

---

## 9. Image OCR: LLM Vision over Tesseract

**Decision:** Send images directly to a vision-capable LLM instead of running OCR locally.

**Alternatives considered:**
- **Tesseract:** Open source, runs locally, no API dependency. But it's bad at document layouts — it extracts raw text without understanding tables, columns, or spatial relationships. For a receipt or invoice image you get a mess of text that's hard to structure.
- **Cloud OCR services (Google Cloud Vision, AWS Textract):** Better quality, but another API dependency and billing surface.

**Why LLM vision:** we're already calling the LLM for schema inference, so sending the image directly means one call instead of two, and the model understands the layout *and* the content simultaneously. The tradeoff: this path only works when the configured provider has a vision model (see decision #5).

---

## 10. UX: Upload-First over Dashboard-First

**Decision:** The home page IS the upload zone. No dashboard, no onboarding wizard, no settings page.

**Alternatives considered:**
- **Dashboard with stats and recent activity:** More "app-like" but adds clicks before the user can do the one thing they came to do — upload a document.
- **Onboarding flow:** "Welcome! Let's set up your first collection." Unnecessary friction for a tool that should be as simple as drag-and-drop.

**Why upload-first:** The fastest path to value is: land on page → drop a file → see structured data. Every click between those steps is a failure. The home page has exactly one affordance: the upload zone. Everything else (collections, queries) follows from that first action.

---

## 11. Testing: Pure Logic over Mock Theater

**Decision:** Unit tests target the deterministic core — the extraction response mapper, SQL identifier sanitizer, confidence calculator, parser format detection, and service-level orchestration — rather than chasing coverage on controllers or mocking the LLM heavily.

**Reasoning:** the failures this system actually hits are malformed LLM responses, unsafe identifiers reaching DDL, and wrong confidence math. Those are all testable without a network. A mocked-LLM integration test mostly verifies the mock. With 5 days, tests that catch real problems beat tests that inflate a coverage number.

---

## 12. What I Deliberately Cut

- **User authentication:** Adds 1-2 days of work (sessions, user-scoped data) for zero evaluation value. The app works as a single-user tool.
- **Async processing / job queue:** Uploads are processed synchronously in the request. For multi-page batches you'd want a queue and status polling; for an evaluation demo, synchronous with a generous timeout is simpler and honest.
- **Word/Excel support:** PDF + Image + CSV + Text covers the real use cases; each extra format is another parsing dependency.
- **Document template system:** Would let users save and reuse schemas. Good feature, but not core — collections already reuse schemas implicitly.
- **Persistent file storage:** Documents are parsed and their data stored, but original files aren't kept. In production you'd keep them for reprocessing; that's a storage management problem, not a data extraction problem.
- **Undo/version history:** Each cell edit is permanent. In production you'd want an audit trail. Cut for scope.
- **LLM cost optimizations (extraction caching by file hash, deterministic CSV fast-path, per-task model routing):** Designed but not built — they optimize an operational cost that doesn't affect the evaluation, and the provider abstraction (#5) already handles the acute problem.

---

## 13. Deployment: Managed Postgres + Container Host for the Backend, Vercel for the Frontend

**Decision:** Deploy the Spring Boot backend and PostgreSQL together on a container platform (Railway/Render), and the Next.js frontend on Vercel with `API_URL` pointing at the backend.

**Alternatives considered:**
- **Everything on Vercel:** Not possible — the backend is a JVM service, not serverless functions. (The SQLite-era plan of "Railway for the persistent filesystem" died with the move to Postgres; a managed database is the right answer now.)
- **Single VM (DigitalOcean/Fly):** Full control, but SSL, process supervision, and DB backups become my problem during a 5-day build.
- **Docker Compose in production:** I use Compose locally for Postgres only; in production a managed database beats a containerized one I have to babysit.

**Tradeoff:** two hosting platforms instead of one. Accepted because each half lands on the platform that's genuinely best at serving it, and the frontend's `/api/*` proxy makes the split invisible to the browser.

---

## 14. CSS: Vanilla CSS over Tailwind

**Decision:** Hand-crafted design system with CSS custom properties instead of Tailwind.

**Alternatives considered:**
- **Tailwind CSS:** Faster to prototype, but the evaluation rubric explicitly calls out UX/design decisions. A Tailwind app looks like a Tailwind app. I wanted full control over the visual identity.
- **CSS Modules:** Good for component scoping, but the global design system approach is more appropriate for a consistent look.
- **Styled-components/Emotion:** Runtime overhead and complexity for no benefit in a small app.

**The design system uses:** a light, calm palette, gradient accents, smooth micro-animations, and the Inter typeface. The goal is "premium tool" not "Bootstrap template."
