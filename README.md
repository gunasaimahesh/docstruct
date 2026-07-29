# DocStruct

**Turn messy documents into structured, queryable data.**

Upload any document — PDF, image, CSV, or text — and get clean, structured data you can search, query, and export. Powered by AI schema inference.

Client-server architecture: a **Next.js frontend** talking to a **Java 21 / Spring Boot 3 backend** over REST.

---

## Quick Start

### Prerequisites

- **Java 21** and **Maven 3.9+**
- **Node.js 18+** and **npm 9+**
- **PostgreSQL 15+** (or Docker, see below)
- **OpenRouter API key** — [get one here](https://openrouter.ai/keys)

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
cp .env.example .env.local        # add your LLM API key (Google AI Studio or OpenRouter)
cd backend
export $(grep -v '^#' ../.env.local | xargs)   # or export the LLM_* vars directly
mvn spring-boot:run
```

The API is now on [http://localhost:8080](http://localhost:8080) — check [http://localhost:8080/api/health](http://localhost:8080/api/health).

### 3. Frontend

```bash
cd frontend
npm install
npm run dev
```

Open [http://localhost:3000](http://localhost:3000). The dev server proxies all `/api/*` calls to the backend (set `API_URL` to point elsewhere).

### Tests

```bash
cd backend && mvn test
```

Unit tests run everywhere. The `DynamicTableRepository` integration test spins up a real PostgreSQL via Testcontainers and is skipped automatically on machines without Docker.

---

## What It Does

1. **Upload** — Drop a messy document (invoice PDF, receipt photo, CSV export, text file)
2. **AI Structures** — The backend parses the document, infers a schema (column names + types), and extracts every data point with confidence scores
3. **Review & Edit** — See the extracted data in a clean table; correct any cell inline
4. **Query** — Ask questions in plain English: "total amount of unpaid invoices" → the LLM generates a (validated, SELECT-only) PostgreSQL query
5. **Export** — Download as CSV or JSON

Other capabilities: **schema evolution** (new documents with unseen fields grow the collection schema), **image OCR via LLM vision** (no local OCR), and **confidence scoring** on every extracted cell.

---

## Architecture

```
frontend/  Next.js + React + TypeScript  (UI only — no server logic)
    │   REST over HTTP (/api/*, proxied in dev)
    ▼
backend/   Java 21 + Spring Boot 3 + Maven
    ├── controller/   REST endpoints (thin, no business logic)
    ├── service/      Ingestion, extraction, query, export workflows
    ├── llm/          OpenRouter client (Gemini 2.5 Flash), prompts, response mapping
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

**Why the hybrid persistence?** Collection and document metadata have a fixed shape — a natural fit for JPA. The extracted data tables cannot be modeled as entities because their columns are inferred by the LLM at upload time, so they are created and queried dynamically through `JdbcTemplate` with sanitized, quoted identifiers.

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
