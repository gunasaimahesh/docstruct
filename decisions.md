# decisions.md — DocStruct

Running log of the real calls I made while building DocStruct.

This is not a changelog. For each decision: **what I chose**, **what I rejected**, **why**, and **what I cut**.

**How to read this:** Start with §1 (problem framing) and §A (architecture). The depth work evaluators care about most is in **§C** — grounding, concurrency, NL2SQL safety, and cost control.

---

## Contents

| Section | Decisions |
|---|---|
| **A. Framing & architecture** | Problem choice · Spring Boot · Postgres hybrid · AI schema · LLM client · Deploy · CSS |
| **B. Product & UX** | Upload-first · Confidence · Schema evolution · NL→SQL · Image OCR · Knowledge layout |
| **C. Hard problems (depth)** | Citation grounding · Concurrent schema lock · AST whitelist · Cache + rate limit |
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

**Decision:** Home page *is* the upload zone. No dashboard, wizard, or settings page.

**Alternatives:** Stats dashboard (extra clicks before the one job). Onboarding flow (friction for drag-and-drop).

**Why:** Fastest path to value: land → drop file → see structured data. Everything else (collections, queries) follows that first action.

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

**Cut for later:** Layout-aware PDF extraction, character offsets + highlight, original-file viewer jump-to-page, confidence-aware SQL, golden regression fixtures, per-field re-extract for Low cells.

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

**Decision:** Plain English → PostgreSQL `SELECT` via LLM, then validate and run against the collection’s real tables. Show generated SQL to the user.

**Alternatives:** Query-builder UI (can’t do “top 5 vendors by spend”). Raw SQL (wrong user). Both later; NL first because harder.

**Safety (two layers):**
1. **Fast textual pass:** SELECT-only (WITH allowed), forbid write keywords, no statement chaining, reject system catalogs.
2. **Authority:** JSQLParser AST — whitelist every table node; query must positively touch this collection’s tables; CTE scope tracked correctly; table-valued functions rejected; parse failure = reject.

**What the regex whitelist got wrong:**
- Comma join: `FROM "data_abc", documents` — second table never checked.
- Comment decoy: `FROM /* "data_abc" */ documents` — pattern matched the comment, approved the wrong table.
- Absence of matches was treated as “safe.”

**Alternatives for validation:** Tighter regexes (doesn’t converge — you reinvent a parser). `libpg_query` (native/JNI cost). `EXPLAIN` plan scraping (brittle JSON; misses dangerous functions). Restricted Postgres role with `GRANT SELECT` only on collection tables — **honest end state**, out of scope (runtime grants + role lifecycle). Parser is defense in depth, not a substitute.

**Load-bearing detail:** Parser returns the first statement and can discard a chained `DROP` — semicolon check stays. Parse runs with a timeout so deep nests fail closed without killing the request thread.

**Tradeoffs:** JSQLParser ≠ Postgres grammar (fail closed). Function blacklist in SELECT is incomplete without restricted roles.

**Tests:** Adversarial unit cases (comma join, comments, CTE leak/shadow, UNION arm, table funcs, unparseable) plus allow-cases so a “reject everything” validator can’t fake green.

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

# D. Process & cuts

## 18. Health endpoint: custom, not Actuator

**Decision:** Hand-rolled `/api/health` — DB connectivity + latency, LLM key present, provider/model.

**Why:** Frontend needs a specific payload. Actuator needs custom indicators + exposure config anyway. ~50 lines beats the dependency for one bespoke probe. In a real fleet with standard probes, Actuator wins.

---

## 19. Testing: pure logic over mock theater

**Decision:** Unit-test the deterministic core — response mapper, SQL sanitizer, confidence scorer, parser detection, query whitelist, cache, rate limit, ingestion merge retry — plus Testcontainers where mocks can’t answer (dynamic DDL, optimistic lock).

**Why:** Real failures are malformed LLM JSON, unsafe identifiers in DDL, wrong confidence math, SQL whitelist holes. Mocked-LLM “integration” mostly tests the mock. Meaningful tests > coverage theater.

**Cut:** Frontend/e2e tests; golden LLM fixture suite (called out as future work under grounding).

---

## 20. What I deliberately cut

| Cut | Why it was right for now |
|---|---|
| **Auth / multi-tenancy** | 1–2 days for little evaluation signal; single-user tool is enough |
| **Async job queue** | Sync upload + generous timeout is honest for a demo; queue becomes necessary when 409 retries aren’t enough |
| **Word / Excel** | PDF + image + CSV + text cover the real cases |
| **Saved templates** | Collections already reuse schemas implicitly |
| **Original file retention / PDF viewer** | Storage + UX problem, not the extraction problem; citations stored but nowhere to jump |
| **Undo / audit trail** | Cell edits are permanent |
| **Streaming exports** | Cap ~100k rows in memory; fine single-user |
| **CSV fast-path / per-task model routing** | Still cut; cache + rate limit (§17) cover the acute spend problem |
| **Restricted DB role for NL2SQL** | Right long-term; parser is what fit the change |
| **Distributed cache / rate limit** | Second datastore declined on purpose |
| **Combining Option 1/2** | Depth > breadth |

---

## How this maps to “above and beyond”

I didn’t add more pages or themes. I owned the hard parts most people skip:

1. **Refuse to be confidently wrong** — citations + deterministic confidence (§9 / §14)
2. **Don’t lose data under concurrency** — optimistic schema merge (§10 / §15)
3. **Don’t trust regex for SQL safety** — AST whitelist (§11 / §16)
4. **Handle real spend pressure** — cache + rate limits (§17)
5. **Layout that fits any document type** — model-reported sections (§13)

Each has alternatives, tradeoffs, and tests that target the failure mode — not a feature checklist.
