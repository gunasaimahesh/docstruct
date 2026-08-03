# decisions.md — DocStruct

Running log of the real calls I made while building DocStruct.

This is not a changelog. For each decision: **what I chose**, **what I rejected**, **why**, and **what I cut**.

**How to read this:** Start with §1 (problem framing) and §A (architecture). The depth work evaluators care about most is in **§C** — grounding, concurrency, NL2SQL safety, cost control, and grounded querying (§22).

---

## Contents

| Section | Decisions |
|---|---|
| **A. Framing & architecture** | Problem choice · Spring Boot · Postgres hybrid · AI schema · LLM client · Deploy · CSS |
| **B. Product & UX** | Upload-first · Confidence · Schema evolution · NL→SQL · Image OCR · Knowledge layout · Grounded answers |
| **C. Hard problems (depth)** | Citation grounding · Concurrent schema lock · AST whitelist · Cache + rate limit · Query intent · Grounded querying (§22) |
| **D. Process & cuts** | Health endpoint · Testing · What I deliberately left out |

---

# A. Framing & architecture

## 1. Problem choice: Option 3

**Decision:** Build “turn messy documents into structured, queryable data.”

**Alternatives:**
- **Option 1 (learn & automate):** Needs reliable generalization from few demos — still unsolved robustly. In 5 days you’d get a brittle cherry-picked demo; the infra (recording, DOM, replay) is heavy with little product payoff.
- **Option 2 (conversation agent):** Feasible, but easy to look like “another ChatGPT wrapper.” Differentiation needs a narrow domain done perfectly — still compared to tools evaluators use daily.

**Why Option 3:**
- Hard sub-problems are concrete: messy PDFs, schema inference across layouts, format variation.
- Instant demo loop: upload → structured data → query.
- Real company pain, not an exercise.
- “Above and beyond” is tangible: grounding, schema evolution, malformed-doc handling.

**Cut:** Combining problems (e.g. chat agent that also structures docs). Depth on one > breadth across two.

---

## 2. Architecture: prototype in Next.js, then a real Spring Boot backend

**Decision:** Final shape is Java 21 / Spring Boot 3 (`backend/`) + Next.js (`frontend/`) over HTTP.

**How it happened:** Days 1–2 were a Next.js monorepo (API routes + SQLite) to find the product shape fast. Once the end-to-end loop worked, the backend was redesigned as a layered service (controller → service → repository, DTOs at the boundary, `@RestControllerAdvice`) — not a line-by-line port.

**Alternatives:**
- **Stay on Next.js API routes:** Fastest ship/deploy. But transactional writes across fixed + dynamic tables, schema evolution, and safe dynamic SQL deserve a real transaction manager.
- **Express/Fastify:** Smaller step from the prototype; same runtime story. If splitting, prefer JVM typing, transactions, Spring Data, PDFBox.

**Tradeoffs:** Two deploy targets + CORS. Mitigated by a Next.js rewrite proxying `/api/*`, so frontend fetch paths never changed. Refactor cost ~1 day of feature work; architectural depth was worth more than another feature.

---

## 3. Persistence: PostgreSQL, hybrid JPA + JdbcTemplate

**Decision:** Postgres two ways on purpose:
- **Spring Data JPA** for fixed metadata (`collections`, `documents`)
- **JdbcTemplate** for per-collection data tables whose columns the LLM invents at upload time

**Alternatives:**
- **SQLite (prototype):** Zero setup; weak concurrency; awkward next to a JVM service. Postgres also gives `JSONB` for cells (value + confidence + provenance).
- **Everything in JPA:** Dynamic tables aren’t compile-time entities. EAV / one JSON “rows” table works, but every query becomes JSON-path gymnastics.
- **Everything in JdbcTemplate:** Consistent, but hand-rolled CRUD for metadata JPA already does well.
- **Document/vector store:** Query patterns are relational (filter, aggregate, join line items). Two datastores for a 5-day build wasn’t worth it; semantic search wasn’t the product.

**Interesting sub-problem:** Each collection gets a real table (`data_<id>`); each `entity_array` column gets a child table joined by `_parent_row_id`. LLM names are sanitized through a strict identifier whitelist before DDL — untrusted schema as injection surface, covered by unit tests.

**Bug this surfaced:** JPA batches until flush; JdbcTemplate inserts in the same TX need the document FK immediately → `saveAndFlush`. One line; knowing why is the point of mixing access styles on purpose.

**Original file bytes (demo):** Uploaded files are kept as Postgres `BYTEA` on `documents` (`original_bytes` + `content_type` + `has_original`), served only via `GET .../documents/{id}/original` — never embedded in collection JSON. Chosen over S3/R2 for demo simplicity: one datastore, delete cascades with the row, Hibernate `ddl-auto: update` adds the columns. Capacity is intentionally small — rate limit + 10MB upload cap still apply; on a ~500MB Railway volume that is tens of max-size files, not an archive. Re-extract-from-original and object storage remain out of scope.

**Why `ddl-auto: update` over Flyway (for now):** Fixed schema is two small tables; the interesting half is created at runtime from LLM output and can’t live in static migrations. Flyway would version two tables and say nothing about the hundred that matter. When the fixed schema grows or multi-env appears, Flyway for the fixed tables.

---

## 4. Schema inference: AI-first, not rule-based

**Decision:** LLM infers schema from content; no per-type hand parsers.

**Alternatives:**
- **Hand parsers per type:** Reliable for known templates; burns 5 days on 2–3 types and breaks on variation.
- **User-defined schemas first:** Simpler build, bad UX — user shouldn’t design structure upfront.
- **Hybrid rules + AI:** Ideal in production; in 5 days better to make one path excellent.

**Why AI-first:** Handles “Total” / “Amount Due” / “Grand Total”; works across types without code changes; prompt engineering is where value lands quickly.

**Hard-won prompt lesson:** Smaller models flatten repeating data (resume achievements → one string). Prompts now forbid multi-item text blobs. Contracts live in `PromptTemplates` so mappers can’t drift silently.

**Tradeoffs:** Hallucinations → confidence/grounding exists. Latency OK for batch, not realtime.

---

## 5. LLM access: thin OpenAI-compatible client, no vendor SDK

**Decision:** One `LlmClient` over chat-completions; base URL / model / key from config only.

**Alternatives:** Official SDKs (vendor lock). Spring AI (heavy for two endpoints; wanted request/retry/token budget explicit).

**Why it paid off:** Started on OpenRouter → Gemini. Credits ran out (402s / truncated JSON). Switched to Groq in minutes via env vars, zero code changes.

**Failure handling:** Linear backoff on transient errors; fail-fast on 4xx; configurable `max_tokens`; strip markdown fences.

**Tradeoff:** Current Groq path has no vision — image uploads need a vision-capable provider. Documented; text/PDF/CSV is the core path.

---

## 6. Deployment: managed Postgres + container backend, Vercel frontend

**Decision:** Backend + Postgres on a container host (Railway/Render); Next.js on Vercel with `API_URL` → backend.

**Alternatives:** All on Vercel (JVM isn’t serverless functions). Single VM (SSL/process/DB backups become my problem in 5 days). Compose-in-prod (Compose is for local Postgres only).

**Tradeoff:** Two platforms. Accepted — each half on what serves it best; `/api/*` proxy hides the split from the browser.

---

## 7. CSS: vanilla design system over Tailwind

**Decision:** CSS custom properties, hand-crafted UI — not Tailwind.

**Alternatives:** Tailwind (faster, looks like every Tailwind app). CSS Modules (fine scoping; global tokens fit better). Emotion/styled-components (runtime cost, no win at this size).

**Intent:** Light, calm palette; clear hierarchy; “focused tool,” not a Bootstrap template. Rubric cares that UX was *chosen*, not that it’s flashy.

---

# B. Product & UX

## 8. Upload-first home, not a dashboard

**Decision:** Home page *is* the upload zone. No dashboard, wizard, or settings page. The zone accepts drag-and-drop, file browse, and a **Paste from clipboard** button (image or plain text → same multipart upload path). Keyboard shortcut paste is not advertised — the button is the discoverable path and uses the Clipboard API with a clear permission/empty-state message.

**Alternatives:** Stats dashboard (extra clicks before the one job). Onboarding flow (friction for drag-and-drop). Relying only on ⌘V (works for power users; invisible to everyone else).

**Why:** Fastest path to value: land → drop/paste file → see structured data. Everything else (collections, queries, Compare) follows that first action.

**Compare tab:** After ingest, reviewers open **Original | Knowledge** side-by-side. Originals come from the dedicated `/original` endpoint (§3); legacy docs without stored bytes show a short empty state.

---

## 9. Confidence is first-class — and computed by us (not the model)

**Decision:** Every cell has high/medium/low confidence, `raw_source`, and evidence. Confidence is derived **server-side** after citation checks. The model’s self-reported confidence is only a penalty.

**Early version (rejected):** Trust the model’s `"confidence": "high"`. Same component that hallucinates also grades itself — a hallucination arrives looking trustworthy. Vocabulary of trust without a mechanism.

**Alternatives considered for scoring:**
- No scores (most tools) — user stares at a table with no signal.
- Binary certain/uncertain — loses the useful middle.
- Vector RAG per field — wrong for single-doc “extract everything”; misses cause invention; adds a second store.
- Logprobs — inconsistent across providers; measures fluency, not presence.
- Second LLM verifier — doubles cost; verifier can hallucinate too.
- Per-type regex extractors — rebuilds rule parsers from §4.

**Why deterministic verification:** Presence, chunk existence, calendar validity, arithmetic — cheap, offline, explainable (“quoted source text does not appear in the document”).

**Design rules (short):**
- Two hard floors → Low: value nowhere in document, or format validation fails.
- Downgrade, never delete/“fix.” Wrong total vs line items: either side could be wrong.
- Chunk index is authority on page number.
- Matching is normalization-aware (`$1,234.50` ↔ `1234.5`, ISO dates, Indian lakh grouping, sign markers like `(-)`).
- Tiered phrase match for multi-column PDF text (strict substring false-alarms on résumés).
- Images → unverifiable (capped Medium), not a fake High.

**Tradeoffs:** Presence ≠ correctness. Stricter prompts lower recall (null over guess). Citations cost tokens. Heuristics can over-flag (only downgrade). Full detail and scoring table live in the README “Reliability” section.

**Cut for later:** Layout-aware PDF extraction, character offsets + highlight, original-file viewer jump-to-page, golden regression fixtures, per-field re-extract for Low cells. Confidence-aware querying is now §22.

---

## 10. Schema evolution: append columns; lock against concurrent loss

**Decision:** New fields on follow-up docs → `ALTER TABLE … ADD COLUMN` + merge into stored schema. Old rows get NULL. Concurrent uploads use `@Version` on `CollectionEntity` and replay the merge up to 3 times; then `409 SCHEMA_UPDATE_CONFLICT` and nothing written.

**Alternatives for evolution:**
- Reject mismatched docs — safe, frustrating.
- Schema-per-document — kills cross-doc query.
- Append-only columns — chosen; least surprising.

**What broke without the lock:** Two uploads each read schema, call LLM, write merge — second write dropped the first’s new column from schema JSON while the physical column stayed. UI/export/query couldn’t see it. Same race lost `document_count` / `row_count`.

**Why retry, not just reject:** Conflict isn’t a user error. Correct outcome (both columns) exists; recompute merge against winner’s schema. **Reuse the LLM result** — never re-extract on retry.

**Alternatives for concurrency:**
- Pessimistic lock across LLM — serializes multi-second I/O.
- `SERIALIZABLE` — whole app pays for one path.
- Atomic SQL JSON merge — merge rules (case-insensitive dedupe, type/description preserve, order) don’t belong in SQL untested.
- Queue / app lock — honest at real load; async queue was cut (§16).
- Leave last-write-leaks — contradicts “don’t lose data quietly.”

**Tradeoffs:** Row-level version conflicts even when schema doesn’t change (counters bump). Three retries is a ceiling. Replay re-runs idempotent DDL (`IF NOT EXISTS`).

**Tests:** Unit tests fake a lost race and assert both columns survive; integration test races two sessions on real Postgres (Testcontainers).

---

## 11. Natural language queries: LLM→SQL, validated on a parsed AST

**Decision:** Plain English → PostgreSQL `SELECT` via LLM, then validate and run against the collection’s real tables. Show generated SQL under “how this was computed.” Structured filters remain the deterministic refine path (§11a); both paths now return a **grounded answer** (§22), not a bare grid.

**Alternatives:** Query-builder UI alone (can’t do “top 5 vendors by spend”). Raw SQL (wrong user). NL-only (too expensive for simple filters).

**Safety (two layers):**
1. **Fast textual pass:** SELECT-only (WITH allowed), forbid write keywords, no statement chaining, reject system catalogs.
2. **Authority:** JSQLParser AST — whitelist every table node; query must positively touch this collection’s tables; CTE scope tracked correctly; table-valued functions rejected; parse failure = reject.

Both layers answer “may this SQL run?”. Whether there was a question to answer at all is decided earlier (§18).

**What the regex whitelist got wrong:**
- Comma join: `FROM "data_abc", documents` — second table never checked.
- Comment decoy: `FROM /* "data_abc" */ documents` — pattern matched the comment, approved the wrong table.
- Absence of matches was treated as “safe.”

**Alternatives for validation:** Tighter regexes (doesn’t converge — you reinvent a parser). `libpg_query` (native/JNI cost). `EXPLAIN` plan scraping (brittle JSON; misses dangerous functions). Restricted Postgres role with `GRANT SELECT` only on collection tables — **honest end state**, out of scope (runtime grants + role lifecycle). Parser is defense in depth, not a substitute.

**Load-bearing detail:** Parser returns the first statement and can discard a chained `DROP` — semicolon check stays. Parse runs with a timeout so deep nests fail closed without killing the request thread.

**Tradeoffs:** JSQLParser ≠ Postgres grammar (fail closed). Function blacklist in SELECT is incomplete without restricted roles.

**Tests:** Adversarial unit cases (comma join, comments, CTE leak/shadow, UNION arm, table funcs, unparseable) plus allow-cases so a “reject everything” validator can’t fake green.

---

## 11a. Structured filters: deterministic refine path (no LLM)

**Decision:** `POST /api/collections/{id}/filter` accepts column / operator / value conditions (+ optional sort / `resultUnit`) and runs parameterized SQL — no LLM. In the UI filters are the **default** surface; AI/natural-language search is hidden behind an “Ask in plain English” toggle.

**Unit of retrieval follows the filter level.** Filtering a nested attribute (e.g. `experience.company = Amazon`) returns the matching **child entries** by default — the Amazon row with title/dates/description — not the whole parent document. A `parent` locator column (person/company name from the main table) is joined on for context. Pass `resultUnit=documents` (UI: “Matching documents”) to keep the older document-centric `EXISTS` path for “which of my 500 résumés mention Amazon.” Main-only filters still return documents. Filters that span two different nested entities also stay document-centric (one row shape can’t be both Experience and Education).

**Why the default flipped:** The first version always returned `SELECT main.*` with nested conditions as `EXISTS` — correct across many docs, useless when drilling into one résumé. Asking about a company and getting the person back (without the matching experience fields) was the wrong unit. Entity-centric matches intent for nested filters; the documents toggle is the escape hatch.

**Why:** Once extraction has done its job, many questions are filter / sort / compare over known columns. Routing those through an LLM adds latency, spend, and failure modes that have nothing to do with the data. Filters stay for the common case; NL stays for phrasing filters can’t express; both feed the same grounded answer card (§22).

**How safety works here:** there is no SQL string to validate after the fact. Columns must already be in the collection schema (sanitized); operators are an enum; every value is a JDBC bind parameter. An injection-shaped value like `x"; DROP TABLE` is a parameter, not SQL. Internal columns (`_row_id`, `_confidence`, …) and `entity_array` nests are rejected. The endpoint is intentionally *not* on the rate limiter — it never spends an LLM call.

**Query hints (same extraction call):** each scalar schema column — top-level *and* nested inside `entitySchema.columns` — may carry a `queryHint` (`filterable`, `sortable`, `groupable`, `role`, `unit`, `example`) inferred once during ingestion. The LLM decides semantics only — never an enum of values. Distinct values from `GET …/columns/{column}/values?entity=…` feed closed-enum dropdowns only (`status`, `currency` on equals). Suggested questions on the answer surface are derived from these hints (no second LLM call).

**Nested entity filters (document path):** `resultUnit=documents` uses correlated `EXISTS`. When `match=all`, conditions on the *same* entity are AND'd inside **one** EXISTS so they must hold on the same child row.

**Alternatives:** Always documents (rejected — wrong for drill-down). Always entries with no toggle (rejected — multi-doc “who worked at Amazon” needs documents). Client-side filter of the loaded page (wrong for pagination).

**Tradeoffs:** No aggregates/group-by in the filter builder yet (“≥ 3 publications” still goes through NL). Empty filters return a page of the whole table. Multi-entity filters can’t return mixed entry types in one result.

**Tests:** Operator → clause + bound params; AND/OR; nested `EXISTS` (documents path); default nested → child rows with parent locator + entity headline; same-entity child predicates; grounded row projection; injection-style values stay in the param list.

---

## 12. Image OCR: vision LLM over Tesseract

**Decision:** Send images to a vision-capable LLM; no local OCR pipeline.

**Alternatives:** Tesseract (bad on layouts). Cloud OCR (extra billing surface). Vision reuses the extraction call and understands layout + content together.

**Tradeoff:** Needs a vision model in config (§5).

---

## 13. Knowledge layout: extraction reports its own sections

**Decision:** Same extraction response returns `documentType` `{ name, category }` and `knowledgeSections` (title, description, columns). Knowledge page renders that — no frontend templates, no second LLM call.

**Bug that motivated it:** UI derived sections from `entity_array` columns. Great for résumés; empty for tax returns, invoices, certificates — extraction worked; layout assumed “resume shape.”

**Alternatives:** Second classify/group call (double cost; model already had the doc in context). Per-type frontend templates (never finishes). Column-name heuristics / mechanical grouping (wrong layer or useless labels). Layout on collection (first doc dictates every later one).

**Design rules:** Both infer + match prompts return layout. Section field names resolved to real columns server-side. Missing type → fallback to schema type name; missing sections → `[]` (honest empty). Pre-existing DB rows without type keep old layout (no paid re-extract).

**Bug surfaced:** Type mismatch on insert (`double precision` vs `varchar`) — coerce to column type or null; value still in `raw_json` for Knowledge.

**Tradeoffs:** Columns left out of every section don’t show on Knowledge (still in table/export/developer). Two docs in one collection may group differently. Extra output tokens.

---

# C. Hard problems (depth)

These are the places I went deeper than “happy path extraction.” Most of the evaluation’s “above and beyond” ask lives here.

## 14. Grounding: verify citations server-side

Covered in detail under **§9**. One-line summary of the hard problem:

> Don’t let the model grade its own homework. Chunk the document, require citations, check them deterministically, and show the user what failed and why.

README has the scoring table and UX split (Knowledge vs Developer Data).

---

## 15. Concurrent schema evolution

Covered under **§10**. Hard problem in one line:

> Don’t lose columns (or counters) when two uploads race the same collection — detect with optimistic lock, re-merge without re-paying for the LLM.

---

## 16. NL2SQL: AST whitelist, not regex

Covered under **§11**. Hard problem in one line:

> Treat “no regex match” as unknown, not safe. Parse the SQL; require positive evidence the query only reads this collection.

---

## 17. LLM spend: content-hash cache + per-client token bucket

**Decision:**
1. **Caffeine cache** keyed by SHA-256 of file bytes (+ serialized schema for follow-up matches). Hits skip the LLM. Failures never cached.
2. **Token bucket** per client IP on model-backed endpoints (create collection, add document, query) — default 20/min, `429` + `Retry-After`.

**Why build it after saying cost “didn’t matter”:** Re-uploading the same docs while tuning grounding burned paid calls for unchanged answers. Switching providers after a 402 was recovery, not a fix. Nothing reduced spend rate or stopped a retry loop from emptying an account.

**Alternatives:**
- Bucket4j — fine at scale; distributed backends are out of scope; bucket is a few dozen lines + injectable clock.
- `@Cacheable` — key isn’t method args (byte digest + schema); SpEL/`KeyGenerator` hides “never cache failure / never lock across LLM.”
- Cache inside `LlmClient` on prompt text — remaps/re-grounds every hit; wrong for NL queries (stale after new docs).
- Hash parsed text — tied to parser behavior; bytes already determine parse.
- Persist hash on `DocumentEntity` — product “duplicate upload” feature, not the cost fix; needs migration story.
- Global / per-collection limits — starve others / no collection yet on create.
- Bare 429 without `Retry-After` — clients guess and retry immediately.

**Design rules:** Separate caches for infer vs match. Load without holding cache lock across the LLM call. Limiter in `preHandle` (throttled upload never parses). Expose `Retry-After` in CORS. Don’t trust `X-Forwarded-For` (use forward-headers strategy in deploy). Idle buckets ≈ full buckets (safe to evict).

**Tradeoffs:** Per-instance only (two replicas = two budgets). Meters requests that *can* hit the model, not actual LLM calls. IP is coarse without auth. Prompt edits can serve stale cache until TTL / `EXTRACTION_CACHE_ENABLED=false`.

**Tests:** Clock-injected refill math; interceptor dispatch for 429 body/header and “handler never called”; cache call-count tests for hit/miss/schema/failure.

---

## 18. Query intent: refuse non-questions instead of answering them

**Decision:** The NL→SQL prompt returns an envelope — `{"answerable": true, "sql": ...}` or `{"answerable": false, "reason": ...}` — and `QueryService` reads the verdict *before* any validation, parsing or execution. A refusal is a successful response carrying the reason, not an error. Any envelope we can’t read (no verdict, not an object, `answerable` with no SQL) is a refusal too.

**How I found it:** Typing “Hi” into the query box returned a result set. The model had been told “if the question cannot be answered from these tables, still return your best-effort query,” so it produced `SELECT *`, which passed the AST whitelist honestly — it *is* this collection’s table. The user got a table and a fluent summary in reply to a greeting. §9 exists so the system won’t be confidently wrong about a value; this was the same failure one level up, about the question.

**Alternatives:**
- **Reject `SELECT *` / broad queries** — the tempting one-liner, and wrong. “Show me everything”, “list all documents”, “what’s in this collection” are real questions whose correct SQL is broad. Query shape does not encode intent; filtering on it buys silence about nonsense by breaking legitimate use.
- **Keyword/regex blocklist of greetings** — “hi”, “thanks”, “how are you” is a list with no end, and it can’t tell “hi, how many invoices are unpaid?” from “hi”.
- **A second LLM classification call** — doubles latency and spend on the interactive path for a decision the model can make in the call it was already making (same reasoning as §13 layout).
- **Validate after generation, then reject if it looks like a dump** — post-hoc shape inspection again, plus it burns the SQL round trip first.
- **Leave it** — it only affects sloppy input. But it teaches the user the box will answer anything, which is exactly the trust the rest of the system is trying to earn.

**Why pre-validation, not a query-shape filter:** The whitelist answers “is this SQL allowed to run?”, which was never the problem — the SQL was allowed and correct. The missing question is “was there anything to answer?”, and that can only be judged from the input, before SQL exists. Deciding earlier also means a refusal never reaches `validateTableReferences`, the database or the summarizer, so no refusal can be a query-shaped bug.

**Few-shot, not one instruction:** A single “don’t answer greetings” line drifts once the schema block grows. The prompt carries worked pairs in *both* directions — greeting and chitchat refused, vague-but-real and legitimately broad accepted — because the failure I was inviting was an over-cautious query box, which is harder to notice than an over-eager one.

**Observability:** Refusals are logged with a running count, so a refusal rate that climbs signals prompt or schema drift rather than users suddenly typing nonsense.

**Tradeoffs:** Intent is now the model’s call, so a badly worded real question can be refused — the refusal says why and costs one retype, where a fabricated answer costs trust. Strict envelope reading means a malformed response refuses a question that might have been answerable. The verdict costs a few output tokens per query.

**Tests:** Greeting, chitchat, reasonless refusal, missing verdict, non-object response and “answerable but no SQL” all return a refusal and assert via mocks that the repository was never touched and the summarizer never ran; broad (“show me everything”) and vague (“anything unusual in here?”) questions still generate SQL and execute, so the suite fails if the box turns cautious.

---

# D. Process & cuts

## 19. Health endpoint: custom, not Actuator

**Decision:** Hand-rolled `/api/health` — DB connectivity + latency, LLM key present, provider/model.

**Why:** Frontend needs a specific payload. Actuator needs custom indicators + exposure config anyway. ~50 lines beats the dependency for one bespoke probe. In a real fleet with standard probes, Actuator wins.

---

## 20. Testing: pure logic over mock theater

**Decision:** Unit-test the deterministic core — response mapper, SQL sanitizer, confidence scorer, parser detection, query whitelist, query refusal, cache, rate limit, ingestion merge retry — plus Testcontainers where mocks can’t answer (dynamic DDL, optimistic lock).

**Why:** Real failures are malformed LLM JSON, unsafe identifiers in DDL, wrong confidence math, SQL whitelist holes. Mocked-LLM “integration” mostly tests the mock. Meaningful tests > coverage theater.

**Cut:** Frontend/e2e tests; golden LLM fixture suite (called out as future work under grounding).

---

## 21. What I deliberately cut

| Cut | Why it was right for now |
|---|---|
| **Auth / multi-tenancy** | 1–2 days for little evaluation signal; single-user tool is enough |
| **Async job queue** | Sync upload + generous timeout is honest for a demo; queue becomes necessary when 409 retries aren’t enough |
| **Word / Excel** | PDF + image + CSV + text cover the real cases |
| **Saved templates** | Collections already reuse schemas implicitly |
| **S3/R2 original storage / re-extract from original** | Demo keeps originals as Postgres BYTEA + Compare tab (§3); object storage and re-ingest from stored bytes are the next step when volume outgrows the disk |
| **Undo / audit trail** | Cell edits are permanent |
| **Streaming exports** | Cap ~100k rows in memory; fine single-user |
| **CSV fast-path / per-task model routing** | Still cut; cache + rate limit (§17) cover the acute spend problem |
| **Restricted DB role for NL2SQL** | Right long-term; parser is what fit the change |
| **Distributed cache / rate limit** | Second datastore declined on purpose |
| **Corpus / cross-collection query** | Heterogeneous schema federation is a real expansion; single-collection grounded answers (§22) first |
| **Combining Option 1/2** | Depth > breadth |

---

## 22. Grounded querying: answer-first, provenance preserved, LLM never computes

**Decision:** A query answer must be as trustworthy as a single extracted cell. Both NL→SQL and structured filters return a grounded result: per-cell confidence + evidence on supporting rows, a deterministic `headline`, an `answerType`, a `coverage` object, and caveats. The UI leads with the answer card; the grid is evidence; SQL/filter is behind “how this was computed.”

**How I found it:** Extraction spends enormous effort on citations and deterministic confidence (§9). Querying then called `InternalColumns.stripAll`, threw provenance away, and asked an LLM to paraphrase a 20-row sample — so a query answer was *less* trustworthy than one cell. “Confidence-aware SQL” had been listed as a cut under §9; this is that cut, owned.

**Alternatives:**
- **Keep grid + LLM summary** — what we had; commodity SQL UI that betrays the grounding thesis.
- **RAG over document text at query time** — second retrieval path; ignores the structured tables we already built.
- **Corpus-wide query across collections** — real product expansion; cut for now (§21).
- **Let the model compute aggregates in prose** — same failure §9 fixed: the component that can hallucinate produces the number.

**Design rules:**
1. **Preserve provenance through the query path.** `_confidence_json` / `_evidence_json` are projected onto each supporting cell; bookkeeping (`_row_id`, …) stays stripped.
2. **Headline is computed from the full result set** — never from a truncated sample, never from the model.
3. **LLM may only phrase already-computed facts.** If phrasing introduces a number absent from those facts, discard it and return the headline.
4. **Coverage is honest.** When provenance exists, report low-confidence cell counts and optional sum-including vs sum-excluding. Bare `SELECT SUM(...)` without source rows → `verifiable: false` with a caveat, not a fake High.
5. **`excludeLowConfidence`** drops supporting rows that contain any low-confidence value cell (filter and NL).

**UI:** One question box; filters collapse under “Refine”; suggested questions from `queryHint`s; refusals render calmly (§18); every supporting cell reuses `DataTable` + `ProvenancePopover`.

**Tradeoffs:** Pure SQL aggregates still can’t attribute per-cell confidence without fetching underlying rows (caveat, don’t fake). Phrasing number-guard is string containment — good enough to catch invented counts, not a full fact checker.

**Tests:** Evidence survives to the answer (guards against re-introducing `stripAll`); mock LLM invents “99” → headline/summary stay on the computed count; confidence-aware sum coverage; exclude-low-confidence row drop; answer-type classification; unverifiable aggregate caveat.

---

## How this maps to “above and beyond”

I didn’t add more pages or themes. I owned the hard parts most people skip:

1. **Refuse to be confidently wrong** — citations + deterministic confidence (§9 / §14)
2. **Don’t lose data under concurrency** — optimistic schema merge (§10 / §15)
3. **Don’t trust regex for SQL safety** — AST whitelist (§11 / §16)
4. **Handle real spend pressure** — cache + rate limits (§17)
5. **Layout that fits any document type** — model-reported sections (§13)
6. **Ground query answers the same way** — provenance through the query path, deterministic headlines, honest coverage (§22)

Each has alternatives, tradeoffs, and tests that target the failure mode — not a feature checklist.
