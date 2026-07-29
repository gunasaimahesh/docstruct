# DocStruct

**Turn messy documents into structured, queryable data.**

Upload any document — PDF, image, CSV, or text — and get clean, structured data you can search, query in plain English, and export. Powered by AI schema inference.

![Java 21](https://img.shields.io/badge/Java-21-orange)
![Spring Boot 3](https://img.shields.io/badge/Spring%20Boot-3-6DB33F)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-17-336791)
![Next.js](https://img.shields.io/badge/Next.js-React%2019-black)
![TypeScript](https://img.shields.io/badge/TypeScript-5-3178C6)

![DocStruct — upload a document, get a queryable table](docs/screenshot.png)

---

## What It Does

1. **Upload** — Drop a messy document (invoice PDF, receipt photo, CSV export, text file)
2. **AI Structures** — The backend parses the document, infers a schema (column names + types), and extracts every data point with confidence scores
3. **Review & Edit** — See the extracted data in a clean table; correct any cell inline
4. **Query** — Ask questions in plain English: "total amount of unpaid invoices" → the LLM generates a validated, SELECT-only PostgreSQL query
5. **Export** — Download as CSV or JSON

- **Schema evolution** — new documents with unseen fields grow the collection schema automatically
- **NL → SQL with guardrails** — generated SQL is whitelist-validated: SELECT-only, scoped to the collection's own tables, system catalogs rejected
- **Image OCR via LLM vision** — no local OCR pipeline needed
- **Confidence scoring** on every extracted cell

---

## Engineering Highlights

- **Hybrid persistence** — Spring Data JPA for fixed-shape metadata, `JdbcTemplate` for data tables whose columns are inferred by the LLM at upload time
- **Dynamic DDL done safely** — tables and columns created at runtime with sanitized, quoted identifiers; nested entities get child tables linked by `_parent_row_id`
- **Defense-in-depth on NL2SQL** — LLM output is parsed, restricted to `SELECT`, checked against a table whitelist, and executed read-only
- **Typed exception hierarchy** mapped to one consistent error contract via `@RestControllerAdvice`; internals never leak in 500 responses
- **Real-database testing** — `DynamicTableRepository` is covered by a Testcontainers integration test against actual PostgreSQL, not mocks
- **Documented trade-offs** — see [decisions.md](decisions.md) for the reasoning behind every major design choice

---

## Architecture

```
frontend/  Next.js + React + TypeScript  (UI only — no server logic)
    │   REST over HTTP (/api/*, proxied in dev)
    ▼
backend/   Java 21 + Spring Boot 3 + Maven
    ├── controller/   REST endpoints (thin, no business logic)
    ├── service/      Ingestion, extraction, query, export workflows
    ├── llm/          Provider-agnostic LLM client, prompts, response mapping
    ├── parser/       PDFBox, Commons CSV, text, image pass-through
    ├── repository/   Spring Data JPA (metadata) + JdbcTemplate (dynamic tables)
    ├── domain/       Entities, schema model, extraction model
    ├── dto/          Request/response records with Bean Validation
    └── exception/    Typed exception hierarchy + @RestControllerAdvice
    │
    ▼
PostgreSQL   collections + documents metadata (JPA/jsonb)
             one dynamically-created data table per collection
             (+ child tables for nested entities, managed via JdbcTemplate)
```

**Why the hybrid persistence?** Collection and document metadata have a fixed shape — a natural fit for JPA. The extracted data tables cannot be modeled as entities because their columns are inferred by the LLM at upload time, so they are created and queried dynamically through `JdbcTemplate` with sanitized, quoted identifiers. Full rationale in [decisions.md](decisions.md).

### REST API

| Method | Path | Purpose |
|--------|------|---------|
| `POST` | `/api/collections` | Upload first document, create collection (multipart) |
| `POST` | `/api/collections/{id}/documents` | Add document to a collection (multipart) |
| `GET` | `/api/collections` | List collections |
| `GET` | `/api/collections/{id}?page=&limit=` | Collection detail + paginated data |
| `DELETE` | `/api/collections/{id}` | Delete collection and its data tables |
| `PATCH` | `/api/collections/{id}/rows/{rowId}` | Edit one data cell |
| `POST` | `/api/collections/{id}/query` | Natural-language query (`{"query": "..."}`) |
| `GET` | `/api/collections/{id}/export?format=csv\|json` | Download data |
| `GET` | `/api/health` | Liveness + DB/LLM dependency status |

Errors always share one contract: `{ "success": false, "error": "...", "code": "...", "details": "..." }` with a meaningful HTTP status.

---

## Quick Start

### Prerequisites

- **Java 21** and **Maven 3.9+**
- **Node.js 18+** and **npm 9+**
- **PostgreSQL 15+** (or Docker, see below)
- **LLM API key** — free options: [Google AI Studio](https://aistudio.google.com/apikey) or [OpenRouter](https://openrouter.ai/keys)

### 1. Database

Either run Postgres via the included compose file:

```bash
docker compose up -d
```

Or use a local install and create the database once:

```bash
createuser -s docstruct
psql -d postgres -c "ALTER USER docstruct WITH PASSWORD 'docstruct';"
createdb -O docstruct docstruct
```

The backend connects to `jdbc:postgresql://localhost:5432/docstruct` with user/password `docstruct`/`docstruct` by default (override with `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`).

### 2. Backend

```bash
cp .env.example .env.local        # add your LLM API key
cd backend
set -a; source ../.env.local; set +a
mvn spring-boot:run
```

The API is now on [http://localhost:8080](http://localhost:8080) — check [http://localhost:8080/api/health](http://localhost:8080/api/health).

### 3. Frontend

```bash
cd frontend
npm install
npm run dev
```

Open [http://localhost:3000](http://localhost:3000) and try it with the sample document in [`samples/`](samples/). The dev server proxies all `/api/*` calls to the backend (set `API_URL` to point elsewhere).

### Tests

```bash
cd backend && mvn test
```

Unit tests run everywhere. The `DynamicTableRepository` integration test spins up a real PostgreSQL via Testcontainers and is skipped automatically on machines without Docker.

---

## Environment Variables

Any OpenAI-compatible chat-completions provider works. The recommended free option is [Google AI Studio](https://aistudio.google.com/apikey) (~1,500 free requests/day for `gemini-2.5-flash`, no credit card needed).

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `LLM_API_KEY` | Yes* | — | LLM provider API key (falls back to `OPENROUTER_API_KEY`) |
| `LLM_BASE_URL` | No | `https://openrouter.ai/api/v1` | Provider base URL. For Google AI Studio use `https://generativelanguage.googleapis.com/v1beta/openai` |
| `LLM_MODEL` | No | `google/gemini-2.5-flash` | Model name. For Google AI Studio use `gemini-2.5-flash` |
| `OPENROUTER_API_KEY` | Yes* | — | Alternative to `LLM_API_KEY` when using OpenRouter |
| `LLM_MAX_TOKENS` | No | `8192` | Cap on `max_tokens` per LLM request. Lower it (e.g. `6000`) if OpenRouter rejects requests with a 402 "requires more credits" error |
| `CORS_ALLOWED_ORIGINS` | No | `http://localhost:3000` | Comma-separated origins allowed to call the API directly (set to the frontend URL in production) |
| `DB_URL` | No | `jdbc:postgresql://localhost:5432/docstruct` | JDBC URL |
| `DB_USERNAME` | No | `docstruct` | Database user |
| `DB_PASSWORD` | No | `docstruct` | Database password |
| `API_URL` | No | `http://localhost:8080` | Backend URL used by the frontend dev proxy |

---

## License

MIT
