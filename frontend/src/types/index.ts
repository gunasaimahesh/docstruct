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

export interface SchemaColumn {
  name: string;
  type: ColumnType;
  description?: string;
  /** Whether this column is required (non-nullable) */
  required: boolean;
  /** If type is 'entity_array', this defines the nested table */
  entitySchema?: EntitySchema;
}

export interface DocumentSchema {
  columns: SchemaColumn[];
  /** AI-detected document type (e.g., "invoice", "receipt", "resume") */
  documentType: string;
  /** Overall confidence in the schema inference */
  confidence: ConfidenceLevel;
}

// ---- Extraction ----

export interface ExtractionCell {
  value: string | number | boolean | null | ExtractionRow[];
  confidence: ConfidenceLevel;
  importance?: ImportanceLevel;
  searchable?: boolean;
  /** Raw text from the original document that this value was extracted from */
  rawSource?: string;
}

export interface ExtractionRow {
  [columnName: string]: ExtractionCell;
}

export interface DocumentAnalysis {
  purpose?: string;
  owner?: string;
  audience?: string;
  useful_data_identified?: string;
  detected_sections?: string[];
  ai_summary?: string;
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
  ai_summary?: string;
  rawJson?: Record<string, unknown>[];
  
  /** Warnings from processing */
  warnings: string[];
  createdAt: string;
}

// ---- Query ----

export interface QueryResult {
  columns: string[];
  rows: Record<string, unknown>[];
  rowCount: number;
  /** The SQL that was generated (for transparency) */
  generatedSql: string;
  /** Natural language summary of results */
  summary?: string;
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
