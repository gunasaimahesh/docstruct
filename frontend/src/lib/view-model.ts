import type { DocumentSchema, EntitySchema, SchemaColumn, ConfidenceLevel, ImportanceLevel, ExtractionRow } from '@/types';

export interface ViewField {
  value: any;
  confidence?: ConfidenceLevel;
  importance?: ImportanceLevel;
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

    // Parse confidence map if present
    let confidenceMap: Record<string, ConfidenceLevel> = {};
    if (typeof row['_confidence_json'] === 'string') {
      try { confidenceMap = JSON.parse(row['_confidence_json']); } catch {}
    }

    for (const col of schema.columns) {
      if (col.type === 'entity_array') {
        // SQL rows won't contain the nested entity array directly here
        // The Collection View handles fetching these separately
        continue;
      }
      
      const value = row[col.name];
      const confidence = confidenceMap[col.name];
      
      // Look for a separate _importance_json or assume it's omitted
      // Currently, we don't store importance per field in SQL unless we add a new column for it
      // For now, we'll map the basic primitive
      
      fields[col.name] = {
        value,
        confidence,
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
