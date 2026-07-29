import type { ConfidenceLevel } from '@/types';
import type { ViewField } from './view-model';

/**
 * Presentation helpers for source attribution.
 *
 * The Knowledge view answers "what was extracted, and can I trust it?", so it
 * speaks only in terms a reader of the document recognises: a page, a quote and a
 * rating. Everything the pipeline needed to produce that answer — chunk indexes,
 * citation corrections, the raw verification notes — belongs on the Developer Data
 * tab, where the question is how the result was produced.
 */

export type ProvenanceStatus = 'verified' | 'review' | 'unverified' | 'absent';

const STATUS_LABELS: Record<ProvenanceStatus, string> = {
  verified: 'Verified',
  review: 'Needs review',
  unverified: 'Could not be verified',
  absent: 'Not found in document',
};

const CONFIDENCE_LABELS: Record<ConfidenceLevel, string> = {
  high: 'High',
  medium: 'Medium',
  low: 'Low',
};

/** Longest source quote shown before it is collapsed behind "Show more". */
export const SNIPPET_LIMIT = 150;

/** How a column type is named to a reader, where the schema's own word reads badly. */
const TYPE_NAMES: Record<string, string> = {
  url: 'web address',
  email: 'email address',
  currency: 'amount',
  boolean: 'yes/no value',
};

export function isMissingValue(field: ViewField) {
  return field.value === null || field.value === undefined || field.value === '';
}

export function provenanceStatus(field: ViewField): ProvenanceStatus {
  if (isMissingValue(field)) return 'absent';
  if (field.confidence === 'low') return 'review';
  return field.evidence?.page !== undefined ? 'verified' : 'unverified';
}

export function statusLabel(status: ProvenanceStatus) {
  return STATUS_LABELS[status];
}

/** "High (1.00)" — the level a reader acts on, with the score that produced it. */
export function confidenceLabel(field: ViewField) {
  if (!field.confidence) return null;
  const level = CONFIDENCE_LABELS[field.confidence];
  const score = field.evidence?.score;
  return score === undefined ? level : `${level} (${score.toFixed(2)})`;
}

export function pageLabel(field: ViewField) {
  const page = field.evidence?.page;
  return page === undefined ? null : `Page ${page}`;
}

export function sourceSnippet(field: ViewField) {
  const snippet = field.metadata?.rawSource;
  return typeof snippet === 'string' && snippet.trim() ? snippet.trim() : null;
}

/**
 * Rewrites a verification note as one sentence a non-developer can act on.
 * Notes that only describe internal bookkeeping (a citation whose page was
 * corrected, for instance) map to nothing at all — the reader has no decision to
 * make about them. The original note stays available under Developer Data.
 */
export function reviewReason(field: ViewField): string | null {
  const note = field.evidence?.note;
  if (!note || provenanceStatus(field) === 'absent') return null;

  // Already written for a person, and more specific than anything generic.
  if (/line items add up to/i.test(note)) return note;

  if (/does not appear anywhere in the document/i.test(note)) {
    return 'The exact wording could not be located in the document text.';
  }
  const validation = note.match(/failed (\w+) validation/i);
  if (validation) {
    const type = validation[1].toLowerCase();
    return `The value does not look like a valid ${TYPE_NAMES[type] ?? type}.`;
  }
  if (/image document/i.test(note)) {
    return 'This document is an image, so its text could not be checked.';
  }
  if (/found elsewhere|does not appear in the cited|does not appear in the quoted/i.test(note)) {
    return 'The value appears in a different part of the document than the extraction indicated.';
  }
  if (/no source chunk was cited|does not exist in this document|no verbatim source quote/i.test(note)) {
    return 'The extraction did not point to a specific place in the document.';
  }
  return null;
}

// ---- Developer Data ----

/** One extracted cell with everything the pipeline recorded about it. */
export interface ProvenanceEntry {
  path: string;
  value: string;
  confidence?: ConfidenceLevel;
  score?: number;
  page?: number;
  chunk?: number;
  note?: string;
  rawSource?: string;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function previewValue(value: unknown): string {
  if (value === null || value === undefined) return 'null';
  if (Array.isArray(value)) return `${value.length} nested row(s)`;
  return String(value);
}

/**
 * Flattens an extraction row into one entry per cell, nested entities included,
 * addressed as `education[0].degree` so an entry can be matched back to the tree.
 */
export function flattenProvenance(row: unknown, prefix = ''): ProvenanceEntry[] {
  if (!isRecord(row)) return [];

  return Object.entries(row).flatMap(([column, cell]) => {
    if (!isRecord(cell)) return [];

    const evidence = isRecord(cell.evidence) ? cell.evidence : {};
    const entry: ProvenanceEntry = {
      path: `${prefix}${column}`,
      value: previewValue(cell.value),
      confidence: typeof cell.confidence === 'string' ? (cell.confidence as ConfidenceLevel) : undefined,
      score: typeof evidence.score === 'number' ? evidence.score : undefined,
      page: typeof evidence.page === 'number' ? evidence.page : undefined,
      chunk: typeof evidence.chunk === 'number' ? evidence.chunk : undefined,
      note: typeof evidence.note === 'string' ? evidence.note : undefined,
      rawSource: typeof cell.rawSource === 'string' ? cell.rawSource : undefined,
    };

    const nested = Array.isArray(cell.value)
      ? cell.value.flatMap((child, index) => flattenProvenance(child, `${prefix}${column}[${index}].`))
      : [];

    return [entry, ...nested];
  });
}
