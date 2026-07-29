import type { CellEvidence, DocumentSchema, EntitySchema, SchemaColumn, ConfidenceLevel, ImportanceLevel, ExtractionRow } from '@/types';

export interface ViewField {
  value: any;
  confidence?: ConfidenceLevel;
  importance?: ImportanceLevel;
  /** Source attribution: page, chunk, score and the reason behind the confidence level */
  evidence?: CellEvidence;
  metadata?: Record<string, any>;
}

export interface ViewRow {
  id: string;
  fields: Record<string, ViewField>;
  // For nested entities attached directly to a row (mostly from JSON)
  children?: Record<string, ViewEntity>; 
}

export interface ViewEntity {
  name: string;
  schema: EntitySchema | DocumentSchema;
  columns: string[];
  rows: ViewRow[];
}

/**
 * Transform hierarchical JSON (ExtractionRow[]) into a ViewEntity.
 */
export function transformJsonToViewModel(
  schema: DocumentSchema | EntitySchema,
  rawRows: ExtractionRow[],
  entityName: string = ('name' in schema ? schema.name : 'Root')
): ViewEntity {
  const columns = schema.columns.map(c => c.name);
  
  const rows: ViewRow[] = rawRows.map((row, index) => {
    const fields: Record<string, ViewField> = {};
    const children: Record<string, ViewEntity> = {};

    for (const col of schema.columns) {
      const cell = row[col.name];
      if (!cell) continue;

      if (col.type === 'entity_array' && col.entitySchema) {
        // Recursively transform child arrays
        if (Array.isArray(cell.value)) {
          children[col.name] = transformJsonToViewModel(
            col.entitySchema,
            cell.value as ExtractionRow[],
            col.name
          );
        }
      } else {
        fields[col.name] = {
          value: cell.value,
          confidence: cell.confidence,
          importance: cell.importance,
          evidence: cell.evidence,
          metadata: cell.rawSource ? { rawSource: cell.rawSource } : undefined
        };
      }
    }

    return {
      id: `row-${index}`,
      fields,
      children: Object.keys(children).length > 0 ? children : undefined,
    };
  });

  return {
    name: entityName,
    schema,
    columns,
    rows
  };
}

/** One entry of the `_evidence_json` map stored on each data-table row. */
interface StoredEvidence extends CellEvidence {
  level?: ConfidenceLevel;
  rawSource?: string;
}

function parseJsonColumn<T>(value: unknown): Record<string, T> {
  if (typeof value !== 'string') return {};
  try {
    const parsed = JSON.parse(value);
    return parsed && typeof parsed === 'object' ? parsed as Record<string, T> : {};
  } catch {
    return {};
  }
}

/**
 * Transform flattened SQL rows into a ViewEntity.
 */
export function transformSqlToViewModel(
  schema: DocumentSchema | EntitySchema,
  sqlRows: Record<string, unknown>[],
  entityName: string = ('name' in schema ? schema.name : 'Root')
): ViewEntity {
  const columns = schema.columns.map(c => c.name);

  const rows: ViewRow[] = sqlRows.map((row) => {
    const fields: Record<string, ViewField> = {};

    // Rows written before source attribution existed only carry _confidence_json,
    // so both columns are read and the richer one wins.
    const confidenceMap = parseJsonColumn<ConfidenceLevel>(row['_confidence_json']);
    const evidenceMap = parseJsonColumn<StoredEvidence>(row['_evidence_json']);

    for (const col of schema.columns) {
      if (col.type === 'entity_array') {
        // SQL rows won't contain the nested entity array directly here
        // The Collection View handles fetching these separately
        continue;
      }

      const value = row[col.name];
      const evidence = evidenceMap[col.name];

      fields[col.name] = {
        value,
        confidence: evidence?.level ?? confidenceMap[col.name],
        evidence: evidence
          ? { page: evidence.page, chunk: evidence.chunk, score: evidence.score, note: evidence.note }
          : undefined,
        metadata: evidence?.rawSource ? { rawSource: evidence.rawSource } : undefined,
      };
    }

    return {
      id: String(row['_row_id'] || Math.random()),
      fields,
    };
  });

  return {
    name: entityName,
    schema,
    columns,
    rows
  };
}
