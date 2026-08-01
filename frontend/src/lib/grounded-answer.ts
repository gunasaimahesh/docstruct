import type { AnswerCoverage, GroundedCell, QueryResult, SchemaColumn } from '@/types';
import type { ViewField, ViewRow } from '@/lib/view-model';

/**
 * Bridges the backend's grounded query result to the same view model the rest of
 * the UI already renders. Every supporting cell keeps its confidence and source
 * evidence, so the answer's rows are as inspectable as an extracted document's.
 */

/** A grounded cell is the `{ value, confidence?, evidence?, rawSource? }` object the backend emits. */
export function isGroundedCell(cell: unknown): cell is GroundedCell {
  return typeof cell === 'object' && cell !== null && 'value' in (cell as Record<string, unknown>);
}

/** The display value of a cell, whether it arrived grounded or as a bare scalar. */
export function cellValue(cell: unknown): unknown {
  return isGroundedCell(cell) ? cell.value : cell;
}

/** Turn one result cell into a ViewField, preserving provenance when present. */
export function toViewField(cell: unknown): ViewField {
  if (!isGroundedCell(cell)) {
    return { value: cell };
  }
  const rawSource = cell.rawSource;
  return {
    value: cell.value,
    confidence: cell.confidence,
    evidence: cell.evidence,
    metadata: rawSource ? { rawSource } : undefined,
  };
}

/** Map the grounded rows of a query result onto ViewRows the DataTable can render. */
export function groundedRowsToViewRows(result: QueryResult): ViewRow[] {
  const columns = result.columns ?? [];
  return (result.rows ?? []).map((row, index) => {
    const fields: Record<string, ViewField> = {};
    for (const column of columns) {
      fields[column] = toViewField(row[column]);
    }
    return { id: `answer-${index}`, fields };
  });
}

/** How many supporting cells were extracted with low confidence. */
export function lowConfidenceCount(result: QueryResult): number {
  return result.coverage?.lowConfidenceCells ?? 0;
}

/**
 * A single honest line about how much of the answer rests on verified extraction.
 * Returns null when there is no per-cell provenance to speak to (e.g. a bare
 * aggregate), so the UI can stay quiet rather than reassure falsely.
 */
export function coverageLine(coverage?: AnswerCoverage | null): string | null {
  if (!coverage || !coverage.verifiable) {
    return null;
  }
  const parts: string[] = [];
  const total = coverage.cellsWithValues;
  const low = coverage.lowConfidenceCells;

  if (total > 0 && low === 0) {
    parts.push(`${total} ${plural(total, 'value')} backed by source evidence.`);
  } else if (low > 0) {
    parts.push(`${low} of ${total} ${plural(total, 'value')} were low-confidence.`);
  }
  if (coverage.excludedRows > 0) {
    parts.push(`${coverage.excludedRows} low-confidence ${plural(coverage.excludedRows, 'row')} excluded.`);
  }
  return parts.length > 0 ? parts.join(' ') : null;
}

/**
 * Example questions seeded from the schema's own query hints. A column that
 * carries an explicit example uses it verbatim; otherwise we synthesise one from
 * the column's semantic role so the query box is never a blank prompt.
 */
export function suggestedQuestions(columns: SchemaColumn[], limit = 4): string[] {
  const questions: string[] = [];
  const seen = new Set<string>();

  const push = (question: string | undefined) => {
    if (!question) return;
    const trimmed = question.trim();
    if (!trimmed || seen.has(trimmed.toLowerCase())) return;
    seen.add(trimmed.toLowerCase());
    questions.push(trimmed);
  };

  for (const column of columns) {
    if (questions.length >= limit) break;
    push(column.queryHint?.example ?? synthesizeQuestion(column));
  }

  return questions.slice(0, limit);
}

function synthesizeQuestion(column: SchemaColumn): string | undefined {
  const hint = column.queryHint;
  const name = column.name;
  const role = hint?.role;

  switch (role) {
    case 'money':
    case 'currency':
      return `What is the total ${name.toLowerCase()}?`;
    case 'status':
      return `How many rows are in each ${name.toLowerCase()}?`;
    case 'person_name':
    case 'company':
    case 'organization':
      return `List the ${name.toLowerCase()}s`;
    case 'date':
      return `Show the most recent ${name.toLowerCase()}`;
    default:
      break;
  }

  if (column.type === 'number' || column.type === 'currency') {
    return `What is the average ${name.toLowerCase()}?`;
  }
  if (hint?.groupable) {
    return `Group by ${name.toLowerCase()}`;
  }
  if (hint?.filterable !== false && column.type === 'text') {
    return `Show all distinct ${name.toLowerCase()}`;
  }
  return undefined;
}

function plural(count: number, noun: string): string {
  return count === 1 ? noun : `${noun}s`;
}
