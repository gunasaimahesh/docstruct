// ============================================================
// DocStruct — Core Type Definitions
// ============================================================

/** Supported document input formats */
export type DocumentFormat = 'pdf' | 'image' | 'csv' | 'text';

/** Inferred column data types */
export type ColumnType = 'text' | 'number' | 'date' | 'currency' | 'boolean' | 'email' | 'url' | 'entity_array' | 'object';

/** Confidence level for an extraction */
export type ConfidenceLevel = 'high' | 'medium' | 'low';

export type ImportanceLevel = 'high' | 'medium' | 'low';

// ---- Schema ----

export interface EntitySchema {
  name: string;
  description?: string;
  columns: SchemaColumn[];
}

/** Semantic role for query UX — inferred once at extraction; values come from the DB. */
export type QueryRole =
  | 'status'
  | 'person_name'
  | 'company'
  | 'organization'
  | 'money'
  | 'currency'
  | 'percentage'
  | 'date'
  | 'phone'
  | 'email'
  | 'url'
  | 'country'
  | 'city'
  | 'identifier'
  | 'description'
  | 'boolean'
  | 'number';

/** LLM-inferred query semantics. Never carries enumerated values. */
export interface QueryHint {
  filterable?: boolean;
  sortable?: boolean;
  groupable?: boolean;
  role?: QueryRole;
  unit?: string;
  example?: string;
}

export interface SchemaColumn {
  name: string;
  type: ColumnType;
  description?: string;
  /** Whether this column is required (non-nullable) */
  required: boolean;
  /** If type is 'entity_array', this defines the nested table */
  entitySchema?: EntitySchema;
  /** Query UX semantics from extraction; absent on older collections */
  queryHint?: QueryHint;
}

export interface DocumentSchema {
  columns: SchemaColumn[];
  /** AI-detected document type (e.g., "invoice", "receipt", "resume") */
  documentType: string;
  /** Overall confidence in the schema inference */
  confidence: ConfidenceLevel;
}

// ---- Extraction ----

/** Where a value came from in the source document, and how it fared in verification */
export interface CellEvidence {
  /** Page of the source document the value was read from */
  page?: number;
  /** Index of the document chunk the value was cited from */
  chunk?: number;
  /** Deterministic 0–1 confidence score that produced the confidence level */
  score?: number;
  /** Plain-English reason for any confidence deduction */
  note?: string;
}

export interface ExtractionCell {
  value: string | number | boolean | null | ExtractionRow[];
  /** Confidence AFTER backend verification, not as self-reported by the model */
  confidence: ConfidenceLevel;
  importance?: ImportanceLevel;
  searchable?: boolean;
  /** Raw text from the original document that this value was extracted from */
  rawSource?: string;
  evidence?: CellEvidence;
}

export interface ExtractionRow {
  [columnName: string]: ExtractionCell;
}

/** What a reader calls the document, as opposed to the snake_case type the schema is keyed by */
export interface DocumentTypeInfo {
  /** Human-readable name, e.g. "Income Tax Return" */
  name: string;
  /** The document family, e.g. "Financial" */
  category?: string;
}

/**
 * One semantic group of fields, inferred per document during extraction.
 * The UI renders exactly these sections — it holds no per-document-type layouts.
 */
export interface KnowledgeSection {
  title: string;
  description?: string;
  /** Schema column names this section covers, in display order */
  fields: string[];
}

export interface DocumentAnalysis {
  purpose?: string;
  owner?: string;
  audience?: string;
  useful_data_identified?: string;
  detected_sections?: string[];
  ai_summary?: string;
  documentType?: DocumentTypeInfo;
  knowledgeSections?: KnowledgeSection[];
}

export interface ExtractionResult {
  schema: DocumentSchema;
  rows: ExtractionRow[];
  analysis?: DocumentAnalysis;
  /** Number of rows successfully extracted */
  rowCount: number;
  /** Warnings encountered during extraction */
  warnings: string[];
}

// ---- Collection ----

export interface Collection {
  id: string;
  name: string;
  description?: string;
  documentType: string;
  schema: DocumentSchema;
  documentCount: number;
  rowCount: number;
  createdAt: string;
  updatedAt: string;
}

// ---- Document ----

export interface Document {
  id: string;
  collectionId: string;
  filename: string;
  format: DocumentFormat;
  /** Size in bytes */
  size: number;
  /** Number of rows extracted from this document */
  rowCount: number;
  /** Overall extraction confidence */
  confidence: ConfidenceLevel;
  
  // Semantic Understanding
  purpose?: string;
  owner?: string;
  audience?: string;
  sections?: string[];
  /** Absent on documents ingested before extraction became document-aware */
  documentType?: DocumentTypeInfo;
  /** Empty when the document has no meaningful grouping of fields */
  knowledgeSections?: KnowledgeSection[];
  ai_summary?: string;
  rawJson?: Record<string, unknown>[];
  
  /** Warnings from processing */
  warnings: string[];
  createdAt: string;
}

// ---- Query ----

export type FilterOperator =
  | 'eq'
  | 'neq'
  | 'contains'
  | 'starts_with'
  | 'ends_with'
  | 'gt'
  | 'gte'
  | 'lt'
  | 'lte'
  | 'is_empty'
  | 'is_not_empty';

export interface FilterCondition {
  column: string;
  operator: FilterOperator | string;
  value?: string | number | boolean | null;
  /** Top-level entity_array column name when filtering a nested attribute */
  entity?: string;
}

export interface FilterRequest {
  filters: FilterCondition[];
  /** AND of all conditions, or OR */
  match?: 'all' | 'any';
  sort?: { column: string; direction: 'asc' | 'desc' };
  page?: number;
  limit?: number;
  /** Drop supporting rows that contain any low-confidence value */
  excludeLowConfidence?: boolean;
}

/** One supporting cell in a grounded query answer. */
export interface GroundedCell {
  value: unknown;
  confidence?: ConfidenceLevel;
  evidence?: CellEvidence;
  rawSource?: string;
}

export type AnswerType = 'single_value' | 'list' | 'aggregate' | 'table';

export interface AnswerCoverage {
  verifiable: boolean;
  rowCount: number;
  includedRows: number;
  excludedRows: number;
  cellsWithValues: number;
  lowConfidenceCells: number;
  aggregateIncludingLow?: number | null;
  aggregateExcludingLow?: number | null;
  aggregateColumn?: string | null;
}

export interface QueryResult {
  columns: string[];
  /** Each cell is a grounded object `{ value, confidence?, evidence?, rawSource? }` */
  rows: Record<string, GroundedCell | unknown>[];
  rowCount: number;
  /** The SQL that was generated (for transparency) */
  generatedSql: string;
  /** Short description of what was run */
  explanation?: string;
  /** Deterministic one-line answer computed from the full result set */
  headline?: string;
  /** Optional phrasing of the same facts; never invents numbers */
  summary?: string;
  answerType?: AnswerType;
  coverage?: AnswerCoverage;
  caveats?: string[];
  /** False when the input was not a question about the collection's data */
  answerable?: boolean;
  /** Why the input could not be answered; present only on a refusal */
  reason?: string;
}

// ---- API Request/Response Types ----

export interface UploadResponse {
  success: boolean;
  collection: Collection;
  document: Document;
  extraction: {
    rowCount: number;
    confidence: ConfidenceLevel;
    warnings: string[];
  };
  error?: string;
}

export interface QueryRequest {
  collectionId: string;
  query: string;
}

export interface QueryResponse {
  success: boolean;
  result?: QueryResult;
  error?: string;
}

export interface CollectionListResponse {
  collections: Collection[];
}

export interface CollectionDetailResponse {
  collection: Collection;
  documents: Document[];
  data: Record<string, unknown>[];
  totalRows: number;
}

export type ExportFormat = 'csv' | 'json';

export interface ErrorResponse {
  success: false;
  error: string;
  code: string;
  details?: string;
}
