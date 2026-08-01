'use client';

import { useEffect, useRef, useState } from 'react';
import type {
  Collection,
  Document,
  ExtractionRow,
  FilterCondition,
  FilterOperator,
  FilterRequest,
  KnowledgeSection,
  QueryResult,
  QueryRole,
  SchemaColumn,
} from '@/types';
import {
  AlertTriangle,
  Award,
  BookOpen,
  Briefcase,
  Building2,
  Calendar,
  CalendarClock,
  CheckCircle,
  ChevronDown,
  ClipboardList,
  Code,
  CreditCard,
  Download,
  FileText,
  FlaskConical,
  GraduationCap,
  Landmark,
  LayoutTemplate,
  Mail,
  MapPin,
  Package,
  Phone,
  Pill,
  Receipt,
  Scale,
  Search,
  Sparkles,
  Stethoscope,
  User,
  Users,
  Wrench,
  Globe,
} from 'lucide-react';
import ConfidenceBadge from './ConfidenceBadge';
import DataTable from './DataTable';
import ExportMenu from './ExportMenu';
import ProvenancePopover from './ProvenancePopover';
import { transformJsonToViewModel, type ViewEntity, type ViewField, type ViewRow } from '@/lib/view-model';
import { flattenProvenance, pageLabel } from '@/lib/provenance';
import { coverageLine, groundedRowsToViewRows, suggestedQuestions } from '@/lib/grounded-answer';

interface DocumentViewerProps {
  collection: Collection;
  document: Document;
  data: Record<string, unknown>;
  onQuery: (query: string, options?: { excludeLowConfidence?: boolean }) => Promise<void>;
  onFilter: (request: FilterRequest) => Promise<void>;
  isQuerying: boolean;
  queryResult: QueryResult | null;
}

type ViewTab = 'overview' | 'knowledge' | 'developer' | 'export';

/** A filterable field — main-table scalar or nested entity_array attribute. */
type FilterField = {
  key: string;
  entity?: string;
  column: SchemaColumn;
  group: string;
};

type FilterRow = {
  id: string;
  fieldKey: string;
  operator: FilterOperator;
  value: string;
};

const TEXT_OPERATORS: Array<{ value: FilterOperator; label: string }> = [
  { value: 'contains', label: 'contains' },
  { value: 'eq', label: 'equals' },
  { value: 'neq', label: 'does not equal' },
  { value: 'starts_with', label: 'starts with' },
  { value: 'ends_with', label: 'ends with' },
  { value: 'is_empty', label: 'is empty' },
  { value: 'is_not_empty', label: 'is not empty' },
];

const SEARCH_OPERATORS: Array<{ value: FilterOperator; label: string }> = [
  { value: 'contains', label: 'contains' },
  { value: 'is_empty', label: 'is empty' },
  { value: 'is_not_empty', label: 'is not empty' },
];

const EQUALITY_OPERATORS: Array<{ value: FilterOperator; label: string }> = [
  { value: 'eq', label: 'equals' },
  { value: 'neq', label: 'does not equal' },
  { value: 'is_empty', label: 'is empty' },
  { value: 'is_not_empty', label: 'is not empty' },
];

const NAME_OPERATORS: Array<{ value: FilterOperator; label: string }> = [
  { value: 'eq', label: 'equals' },
  { value: 'contains', label: 'contains' },
  { value: 'neq', label: 'does not equal' },
  { value: 'is_empty', label: 'is empty' },
  { value: 'is_not_empty', label: 'is not empty' },
];

const NUMBER_OPERATORS: Array<{ value: FilterOperator; label: string }> = [
  { value: 'eq', label: 'equals' },
  { value: 'neq', label: 'does not equal' },
  { value: 'gt', label: 'greater than' },
  { value: 'gte', label: 'at least' },
  { value: 'lt', label: 'less than' },
  { value: 'lte', label: 'at most' },
  { value: 'is_empty', label: 'is empty' },
  { value: 'is_not_empty', label: 'is not empty' },
];

const DATE_OPERATORS: Array<{ value: FilterOperator; label: string }> = [
  { value: 'eq', label: 'on' },
  { value: 'gt', label: 'after' },
  { value: 'gte', label: 'on or after' },
  { value: 'lt', label: 'before' },
  { value: 'lte', label: 'on or before' },
  { value: 'is_empty', label: 'is empty' },
  { value: 'is_not_empty', label: 'is not empty' },
];

const BOOLEAN_OPERATORS: Array<{ value: FilterOperator; label: string }> = [
  { value: 'eq', label: 'is' },
  { value: 'is_empty', label: 'is empty' },
  { value: 'is_not_empty', label: 'is not empty' },
];

/** Truly low-cardinality — hard dropdown on equals only. */
const CLOSED_ENUM_ROLES = new Set<QueryRole>(['status', 'currency']);

/** High-ish cardinality — distinct values are suggestions, never a cage. */
const SUGGEST_ROLES = new Set<QueryRole>(['company', 'organization', 'country', 'city']);

const PATTERN_OPERATORS = new Set<FilterOperator>(['contains', 'starts_with', 'ends_with']);

function fieldKey(entity: string | undefined, columnName: string) {
  return entity ? `${entity}::${columnName}` : `::${columnName}`;
}

function roleOf(column?: SchemaColumn): QueryRole | undefined {
  return column?.queryHint?.role;
}

/** Prose / list text — keep searchable via contains, don't hide from the picker. */
function isProseColumn(column?: SchemaColumn): boolean {
  if (!column) return false;
  const role = roleOf(column);
  if (role === 'description') return true;
  return column.queryHint?.filterable === false
    && (column.type === 'text' || column.type === 'object');
}

function isClosedEnumColumn(column?: SchemaColumn): boolean {
  const role = roleOf(column);
  return role != null && CLOSED_ENUM_ROLES.has(role);
}

/** Company/org/city/… — load DISTINCT as typeahead suggestions, not a closed list. */
function isSuggestColumn(column?: SchemaColumn): boolean {
  if (!column || isClosedEnumColumn(column)) return false;
  const role = roleOf(column);
  if (role != null && SUGGEST_ROLES.has(role)) return true;
  return column.queryHint?.groupable === true;
}

function operatorsForColumn(column?: SchemaColumn) {
  const role = roleOf(column);
  const type = column?.type ?? 'text';

  if (isProseColumn(column)) return SEARCH_OPERATORS;
  if (role === 'boolean' || type === 'boolean') return BOOLEAN_OPERATORS;
  if (role === 'date' || type === 'date') return DATE_OPERATORS;
  if (role === 'money' || role === 'number' || role === 'percentage'
      || type === 'number' || type === 'currency') {
    return NUMBER_OPERATORS;
  }
  if (isClosedEnumColumn(column)) return EQUALITY_OPERATORS;
  // Suggest roles get equals + contains — widget depends on the operator chosen.
  if (isSuggestColumn(column) || role === 'person_name' || role === 'company' || role === 'organization') {
    return NAME_OPERATORS;
  }
  if (role === 'email' || role === 'url' || role === 'phone' || role === 'identifier'
      || type === 'email' || type === 'url') {
    return NAME_OPERATORS;
  }
  return TEXT_OPERATORS;
}

function operatorNeedsValue(operator: FilterOperator) {
  return operator !== 'is_empty' && operator !== 'is_not_empty';
}

type ValueControl = 'none' | 'boolean' | 'date' | 'closed-select' | 'text';

/** Value widget follows the operator — pattern ops and company/org are plain text (no hints). */
function valueControlFor(column: SchemaColumn | undefined, operator: FilterOperator): ValueControl {
  if (!operatorNeedsValue(operator)) return 'none';
  const role = roleOf(column);
  const type = column?.type ?? 'text';
  if (role === 'boolean' || type === 'boolean') return 'boolean';
  if (role === 'date' || type === 'date') return 'date';
  if (PATTERN_OPERATORS.has(operator)) return 'text';
  // Closed enums only: hard dropdown. Everything else (incl. company) is an empty text box.
  if (isClosedEnumColumn(column) && (operator === 'eq' || operator === 'neq')) return 'closed-select';
  return 'text';
}

function valuePlaceholder(column?: SchemaColumn): string {
  // No example/role hints in the box — keep it empty of suggestions.
  const role = roleOf(column);
  const type = column?.type ?? 'text';
  if (role === 'date' || type === 'date') return 'YYYY-MM-DD';
  return '';
}

function buildFilterFields(columns: SchemaColumn[]): FilterField[] {
  const fields: FilterField[] = [];
  for (const column of columns) {
    if (column.type === 'entity_array' && column.entitySchema?.columns?.length) {
      const group = formatLabel(column.name);
      for (const nested of column.entitySchema.columns) {
        if (nested.type === 'entity_array') continue;
        fields.push({
          key: fieldKey(column.name, nested.name),
          entity: column.name,
          column: nested,
          group,
        });
      }
    } else if (column.type !== 'entity_array') {
      fields.push({
        key: fieldKey(undefined, column.name),
        column,
        group: 'Document fields',
      });
    }
  }
  return fields;
}

function isColumnSortable(column: SchemaColumn): boolean {
  if (column.type === 'entity_array') return false;
  if (column.queryHint?.sortable === false) return false;
  if (isProseColumn(column)) return false;
  return true;
}

function newFilterRow(fields: FilterField[]): FilterRow {
  const first = fields[0];
  const ops = operatorsForColumn(first?.column);
  return {
    id: `${Date.now()}-${Math.random().toString(36).slice(2, 7)}`,
    fieldKey: first?.key ?? '',
    operator: ops[0]?.value ?? 'contains',
    value: '',
  };
}

type ProfileField = {
  label: 'Name' | 'Email' | 'Phone' | 'Location' | 'LinkedIn' | 'GitHub' | 'Portfolio';
  value: string;
  href?: string;
};

/**
 * Section titles are written by the extraction, not chosen from a list, so the icon is
 * matched on what the title talks about. Anything unrecognised still gets a section.
 */
function getSectionIcon(name: string) {
  const normalised = name.toLowerCase();
  if (/patient|diagnos|symptom|medical/.test(normalised)) return <Stethoscope size={17} />;
  if (/medication|prescription|dosage|drug/.test(normalised)) return <Pill size={17} />;
  if (/lab|test result|specimen|vital/.test(normalised)) return <FlaskConical size={17} />;
  if (/account|balance|bank|statement/.test(normalised)) return <Landmark size={17} />;
  if (/transaction|payment|invoice|billing|charge/.test(normalised)) return <CreditCard size={17} />;
  if (/tax|refund|income|financial|salary|compensation/.test(normalised)) return <Receipt size={17} />;
  if (/item|product|line|goods|quantit/.test(normalised)) return <Package size={17} />;
  if (/vendor|supplier|customer|client|company|organisation|organization|employer/.test(normalised)) return <Building2 size={17} />;
  if (/part(y|ies)|signator|beneficiar/.test(normalised)) return <Users size={17} />;
  if (/term|clause|obligation|condition|legal|contract|liabilit/.test(normalised)) return <Scale size={17} />;
  if (/date|period|deadline|duration|filing|validity/.test(normalised)) return <CalendarClock size={17} />;
  if (/contact|profile|name|taxpayer|holder|applicant|personal|identit/.test(normalised)) return <User size={17} />;
  if (/experience|job|work|employment|position/.test(normalised)) return <Briefcase size={17} />;
  if (/education|school|academic|degree|qualification/.test(normalised)) return <GraduationCap size={17} />;
  if (/skill|technolog|tool|project/.test(normalised)) return <Wrench size={17} />;
  if (/achievement|award|publication|certificat|honour|honor/.test(normalised)) return <Award size={17} />;
  if (/summary|total|overview|detail/.test(normalised)) return <ClipboardList size={17} />;
  return <LayoutTemplate size={17} />;
}

function formatLabel(value: string) {
  return value.replace(/_/g, ' ');
}

function titleCase(value: string) {
  return value.replace(/(^|\s)(\S)/g, (_, space, letter) => space + letter.toUpperCase());
}

/**
 * Section titles drop redundant structural suffixes like "Array" or "List". A title written
 * by the extraction is prose and is kept as written, except that a document shouting its
 * headings in capitals ("EXPERIENCE") should not make the page shout too. Column names
 * standing in as titles get capitalised.
 */
function formatSectionLabel(value: string) {
  const label = formatLabel(value).replace(/\s+(array|list)$/i, '').trim() || formatLabel(value);
  if (label === label.toUpperCase() && label !== label.toLowerCase()) return titleCase(label.toLowerCase());
  if (/[A-Z]/.test(label)) return label;
  return titleCase(label);
}

/** "Financial" is a family name, so the UI states what it is: "Financial document". */
function formatCategory(category: string) {
  const trimmed = category.trim();
  return /document|report|record|statement|form|letter|certificate/i.test(trimmed)
    ? trimmed
    : `${trimmed} document`;
}

function formatDate(value: string) {
  try {
    return new Intl.DateTimeFormat(undefined, { dateStyle: 'medium' }).format(new Date(value));
  } catch {
    return value;
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function unwrapExtractionValue(value: unknown): unknown {
  if (isRecord(value) && 'value' in value) return unwrapExtractionValue(value.value);
  if (typeof value === 'string') {
    const trimmed = value.trim();
    if ((trimmed.startsWith('{') && trimmed.endsWith('}')) || (trimmed.startsWith('[') && trimmed.endsWith(']'))) {
      try {
        return unwrapExtractionValue(JSON.parse(trimmed));
      } catch {
        return value;
      }
    }
  }
  return value;
}

function normaliseKey(key: string) {
  return key.toLowerCase().replace(/[^a-z0-9]/g, '');
}

function findProfileValue(value: unknown, aliases: string[], depth = 0): string | undefined {
  if (depth > 5) return undefined;
  const unwrapped = unwrapExtractionValue(value);
  if (Array.isArray(unwrapped)) {
    for (const item of unwrapped) {
      const match = findProfileValue(item, aliases, depth + 1);
      if (match) return match;
    }
    return undefined;
  }
  if (!isRecord(unwrapped)) return undefined;

  for (const alias of aliases) {
    const matchingKey = Object.keys(unwrapped).find((key) => normaliseKey(key) === normaliseKey(alias));
    if (matchingKey) {
      const candidate = unwrapExtractionValue(unwrapped[matchingKey]);
      if (typeof candidate === 'string' || typeof candidate === 'number') return String(candidate);
    }
  }

  for (const nestedValue of Object.values(unwrapped)) {
    const match = findProfileValue(nestedValue, aliases, depth + 1);
    if (match) return match;
  }
  return undefined;
}

function findTopLevelProfileValue(data: Record<string, unknown>, aliases: string[]): string | undefined {
  for (const alias of aliases) {
    const matchingKey = Object.keys(data).find((key) => normaliseKey(key) === normaliseKey(alias));
    if (!matchingKey) continue;
    const value = unwrapExtractionValue(data[matchingKey]);
    if (typeof value === 'string' || typeof value === 'number') return String(value);
  }
  return undefined;
}

function makeLink(value: string, prefix?: string) {
  if (prefix) return `${prefix}${value}`;
  return value.startsWith('http://') || value.startsWith('https://') ? value : `https://${value}`;
}

const PROFILE_DEFINITIONS: Array<{ label: ProfileField['label']; aliases: string[]; prefix?: string }> = [
  { label: 'Name', aliases: ['candidate_name', 'full_name', 'name'] },
  { label: 'Email', aliases: ['email', 'email_address'], prefix: 'mailto:' },
  { label: 'Phone', aliases: ['phone', 'phone_number', 'mobile', 'mobile_number'], prefix: 'tel:' },
  { label: 'Location', aliases: ['location', 'address', 'city'] },
  { label: 'LinkedIn', aliases: ['linkedin', 'linkedin_profile', 'linkedin_url'] },
  { label: 'GitHub', aliases: ['github', 'github_profile', 'github_url'] },
  { label: 'Portfolio', aliases: ['portfolio', 'portfolio_url', 'website', 'personal_website'] },
];

function getProfileFields(data: Record<string, unknown>): ProfileField[] {
  const contactSources = Object.entries(data)
    .filter(([key]) => /contact|profile|candidate|person/i.test(key))
    .map(([, value]) => value);

  return PROFILE_DEFINITIONS.flatMap((definition) => {
    const value = findTopLevelProfileValue(data, definition.aliases)
      || contactSources.map((source) => findProfileValue(source, definition.aliases)).find(Boolean);
    if (!value) return [];
    const isLink = definition.label === 'Email' || definition.label === 'Phone' || ['LinkedIn', 'GitHub', 'Portfolio'].includes(definition.label);
    return [{ label: definition.label, value, href: isLink ? makeLink(value, definition.prefix) : undefined }];
  });
}

/**
 * The top-level column keys the Overview "Profile" card is already showing, so the
 * Knowledge view can drop them and not present the same contact details twice.
 */
function getProfileFieldKeys(data: Record<string, unknown>): Set<string> {
  const keys = new Set<string>();
  for (const definition of PROFILE_DEFINITIONS) {
    for (const alias of definition.aliases) {
      const matchingKey = Object.keys(data).find((key) => normaliseKey(key) === normaliseKey(alias));
      if (matchingKey) keys.add(normaliseKey(matchingKey));
    }
  }
  return keys;
}

/** A backend-declared section resolved against the values actually extracted. */
type KnowledgeView = {
  title: string;
  description?: string;
  /** Scalar fields, rendered as a definition list */
  fields: Array<[string, ViewField]>;
  /** Repeating sub-records, rendered as tables */
  entities: Array<[string, ViewEntity]>;
};

/**
 * Every schema column must appear in Knowledge. Older documents (or a forgetful model)
 * may omit columns from knowledgeSections — append those in schema order, titled from
 * the column name. No per-document-type templates; coverage comes from the schema.
 */
function completeDeclaredSections(
  declared: KnowledgeSection[],
  schemaColumns: Array<{ name: string; description?: string | null }>,
  excludeKeys: Set<string>,
): KnowledgeSection[] {
  const assigned = new Set(declared.flatMap((section) => section.fields.map(normaliseKey)));
  const completed = [...declared];
  for (const column of schemaColumns) {
    const key = normaliseKey(column.name);
    if (!key || excludeKeys.has(key) || assigned.has(key)) continue;
    completed.push({
      title: formatSectionLabel(column.name),
      description: column.description ?? undefined,
      fields: [column.name],
    });
    assigned.add(key);
  }
  return completed;
}

function resolveSections(
  declared: KnowledgeSection[],
  row: ViewRow | undefined,
  excludeKeys: Set<string>,
): KnowledgeView[] {
  return declared
    .map((section) => ({
      title: section.title,
      description: section.description,
      // Contact fields already live in the Overview Profile card; skip them here so
      // the same details are not shown twice. A section left with nothing is dropped.
      fields: section.fields.flatMap((field) =>
        !excludeKeys.has(normaliseKey(field)) && row?.fields[field]
          ? [[field, row.fields[field]] as [string, ViewField]]
          : []),
      entities: section.fields.flatMap((field) => (row?.children?.[field] ? [[field, row.children[field]] as [string, ViewEntity]] : [])),
    }))
    .filter((section) => section.fields.length > 0 || section.entities.length > 0);
}

/**
 * Documents ingested before section detection existed: show every top-level scalar and
 * every nested entity from the schema/view model, still with no type-specific templates.
 */
function legacySections(row: ViewRow | undefined): KnowledgeView[] {
  const fromChildren = Object.entries(row?.children ?? {}).map(([name, entity]) => ({
    title: name,
    fields: [] as Array<[string, ViewField]>,
    entities: [[name, entity]] as Array<[string, ViewEntity]>,
  }));
  const scalarFields = Object.entries(row?.fields ?? {});
  if (scalarFields.length === 0) return fromChildren;
  return [
    ...scalarFields.map(([name, field]) => ({
      title: formatSectionLabel(name),
      fields: [[name, field]] as Array<[string, ViewField]>,
      entities: [] as Array<[string, ViewEntity]>,
    })),
    ...fromChildren,
  ];
}

function sectionCountLabel(section: KnowledgeView) {
  const parts = section.entities.map(([name, entity]) => {
    const count = `${entity.rows.length} ${entity.rows.length === 1 ? 'record' : 'records'}`;
    // The entity is usually the whole section, in which case naming it again just repeats the title.
    return formatSectionLabel(name) === formatSectionLabel(section.title)
      ? count
      : `${formatSectionLabel(name)}: ${entity.rows.length}`;
  });
  if (section.fields.length > 0) {
    parts.unshift(`${section.fields.length} ${section.fields.length === 1 ? 'field' : 'fields'}`);
  }
  return parts.length > 0 ? parts.join(' · ') : 'nothing extracted';
}

export default function DocumentViewer({ collection, document, data, onQuery, onFilter, isQuerying, queryResult }: DocumentViewerProps) {
  const [activeTab, setActiveTab] = useState<ViewTab>('overview');
  const [query, setQuery] = useState('');
  const [showRefine, setShowRefine] = useState(false);
  const [showHowComputed, setShowHowComputed] = useState(false);
  const [excludeLowConfidence, setExcludeLowConfidence] = useState(false);
  const filterFields = buildFilterFields(collection.schema.columns);
  const filterGroups = [...new Map(filterFields.map((field) => [field.group, filterFields.filter((item) => item.group === field.group)])).entries()];
  const sortableColumns = collection.schema.columns.filter(isColumnSortable);
  const questionSuggestions = suggestedQuestions(collection.schema.columns);
  const [filterRows, setFilterRows] = useState<FilterRow[]>(() =>
    filterFields.length > 0 ? [newFilterRow(filterFields)] : []);
  const [match, setMatch] = useState<'all' | 'any'>('all');
  const [sortColumn, setSortColumn] = useState('');
  const [sortDirection, setSortDirection] = useState<'asc' | 'desc'>('asc');
  /** Distinct values keyed by field key — loaded lazily from the data table. */
  const [distinctValues, setDistinctValues] = useState<Record<string, string[]>>({});
  const [distinctLoading, setDistinctLoading] = useState<Record<string, boolean>>({});
  const distinctRequested = useRef(new Set<string>());

  const rootEntity = transformJsonToViewModel(collection.schema, [data as ExtractionRow], collection.name);
  const rootRow = rootEntity.rows[0];
  const documentTypeName = document.documentType?.name || formatLabel(collection.documentType);
  const documentCategory = document.documentType?.category;
  const profileFields = getProfileFields(data);
  // Only fold contact fields out of Knowledge when the Profile card is actually showing them.
  const profileKeys = profileFields.length > 0 ? getProfileFieldKeys(data) : new Set<string>();
  // Schema is the source of coverage; LLM section titles are only a preferred grouping.
  const sections = document.documentType
    ? resolveSections(
        completeDeclaredSections(document.knowledgeSections ?? [], collection.schema.columns, profileKeys),
        rootRow,
        profileKeys,
      )
    : legacySections(rootRow);
  const provenanceEntries = flattenProvenance(data);

  const fieldByKey = (key: string) => filterFields.find((field) => field.key === key);

  // Lazy-load DISTINCT only for closed-enum dropdowns (status / currency).
  const distinctFieldKeys = filterRows
    .filter((row) => {
      const field = fieldByKey(row.fieldKey);
      if (!field) return false;
      return valueControlFor(field.column, row.operator) === 'closed-select';
    })
    .map((row) => row.fieldKey)
    .join('\0');

  useEffect(() => {
    if (!distinctFieldKeys) return;
    const fields = buildFilterFields(collection.schema.columns);
    for (const key of distinctFieldKeys.split('\0')) {
      const field = fields.find((item) => item.key === key);
      if (!field) continue;
      const requestKey = `${collection.id}:${key}`;
      if (distinctRequested.current.has(requestKey)) continue;
      distinctRequested.current.add(requestKey);
      setDistinctLoading((prev) => ({ ...prev, [key]: true }));
      const params = new URLSearchParams();
      if (field.entity) params.set('entity', field.entity);
      const qs = params.toString();
      void fetch(`/api/collections/${collection.id}/columns/${encodeURIComponent(field.column.name)}/values${qs ? `?${qs}` : ''}`)
        .then(async (res) => {
          const body = res.ok ? await res.json() as { values?: string[] } : { values: [] };
          setDistinctValues((prev) => ({ ...prev, [key]: body.values ?? [] }));
        })
        .catch(() => {
          setDistinctValues((prev) => ({ ...prev, [key]: [] }));
        })
        .finally(() => {
          setDistinctLoading((prev) => ({ ...prev, [key]: false }));
        });
    }
  }, [distinctFieldKeys, collection.id, collection.schema.columns]);

  const updateFilterRow = (id: string, patch: Partial<FilterRow>) => {
    setFilterRows((rows) => rows.map((row) => {
      if (row.id !== id) return row;
      const next = { ...row, ...patch };
      if (patch.fieldKey && patch.fieldKey !== row.fieldKey) {
        const field = fieldByKey(patch.fieldKey);
        const ops = operatorsForColumn(field?.column);
        if (!ops.some((op) => op.value === next.operator)) {
          next.operator = ops[0]?.value ?? 'contains';
        }
        next.value = '';
      }
      // Switching to a free-text pattern op keeps the typed value; switching into a
      // closed select clears a value that isn't in the option list (handled by the select).
      return next;
    }));
  };

  const submitFilter = (event: React.FormEvent) => {
    event.preventDefault();
    if (isQuerying) return;

    const filters: FilterCondition[] = [];
    for (const row of filterRows) {
      const field = fieldByKey(row.fieldKey);
      if (!field) continue;
      const needsValue = operatorNeedsValue(row.operator);
      if (needsValue && !row.value.trim()) continue;
      const type = field.column.type ?? 'text';
      let value: string | number | boolean | null = row.value;
      if (needsValue && (type === 'number' || type === 'currency')) {
        const parsed = Number(row.value.replace(/[,$€£¥₹\s]/g, ''));
        if (Number.isNaN(parsed)) {
          continue;
        }
        value = parsed;
      } else if (needsValue && type === 'boolean') {
        const lowered = row.value.trim().toLowerCase();
        if (['true', 'yes', '1', 'y'].includes(lowered)) value = true;
        else if (['false', 'no', '0', 'n'].includes(lowered)) value = false;
        else continue;
      }
      filters.push({
        column: field.column.name,
        operator: row.operator,
        value: needsValue ? value : null,
        ...(field.entity ? { entity: field.entity } : {}),
      });
    }

    const request: FilterRequest = {
      filters,
      match,
      page: 1,
      limit: 100,
      excludeLowConfidence: excludeLowConfidence || undefined,
    };
    if (sortColumn) {
      request.sort = { column: sortColumn, direction: sortDirection };
    }
    void onFilter(request);
  };

  const submitQuery = (event: React.FormEvent) => {
    event.preventDefault();
    if (query.trim() && !isQuerying) {
      void onQuery(query.trim(), { excludeLowConfidence: excludeLowConfidence || undefined });
    }
  };

  const runSuggestedQuery = (suggestion: string) => {
    setQuery(suggestion);
    void onQuery(suggestion, { excludeLowConfidence: excludeLowConfidence || undefined });
  };

  const answerCoverage = queryResult ? coverageLine(queryResult.coverage) : null;
  const answerRows = queryResult && queryResult.answerable !== false
    ? groundedRowsToViewRows(queryResult)
    : [];

  const renderFieldValue = (field: ViewField) => {
    // Stated as absent rather than blank: a field the document does not contain is
    // a fact about the document, not a gap in the UI.
    if (field.value === null || field.value === undefined || field.value === '') return <span className="empty-value">Not found in document</span>;
    const value = String(field.value);
    if (value.startsWith('http')) return <a href={value} target="_blank" rel="noreferrer">{value}</a>;
    if (value.includes('@') && !value.includes(' ')) return <a href={`mailto:${value}`}>{value}</a>;
    return value;
  };

  /** Source attribution shown beside every extracted field. */
  const renderFieldProvenance = (column: string, field: ViewField) => {
    const page = pageLabel(field);
    if (!field.confidence && !page) return null;

    return (
      <div className="entity-field-meta">
        {field.confidence && <ConfidenceBadge level={field.confidence} score={field.evidence?.score} />}
        {page && <span className="citation-chip" title={`Read from ${page.toLowerCase()} of the document`}>{page}</span>}
        <ProvenancePopover label={formatLabel(column)} field={field} />
      </div>
    );
  };

  const renderFieldGrid = (fields: Array<[string, ViewField]>) => (
    <dl className="entity-field-grid">
      {fields.map(([column, field]) => (
        <div key={column} className={`entity-field${field.confidence === 'low' ? ' entity-field-flagged' : ''}`}>
          <dt>{formatLabel(column)}</dt>
          <dd>{renderFieldValue(field)}</dd>
          {renderFieldProvenance(column, field)}
        </div>
      ))}
    </dl>
  );

  const renderSection = (section: KnowledgeView, index: number) => {
    const isEmpty = section.fields.length === 0 && section.entities.length === 0;
    // Entity tables carry their own heading only when the section holds more than one thing.
    const labelEntities = section.entities.length > 1 || section.fields.length > 0;

    return (
      <details className="knowledge-section-card" key={`${section.title}-${index}`} open={index === 0}>
        <summary>
          <span className="section-icon">{getSectionIcon(section.title)}</span>
          <span className="section-summary-text">
            <strong>{formatSectionLabel(section.title)}</strong>
            <small>{sectionCountLabel(section)}</small>
          </span>
          <ChevronDown className="summary-chevron" size={18} />
        </summary>
        <div className="knowledge-section-body">
          {section.description && <p className="knowledge-section-description">{section.description}</p>}
          {isEmpty && <p className="card-empty">No extracted data is available for this section.</p>}
          {section.fields.length > 0 && renderFieldGrid(section.fields)}
          {section.entities.map(([name, entity]) => (
            <div className="knowledge-subsection" key={name}>
              {labelEntities && <h3>{formatSectionLabel(name)}</h3>}
              <DataTable columns={entity.columns} rows={entity.rows} showMetadata />
            </div>
          ))}
        </div>
      </details>
    );
  };

  return (
    <div className="document-workspace">
      <nav className="document-tabs" aria-label="Document sections">
        <button className={activeTab === 'overview' ? 'active' : ''} onClick={() => setActiveTab('overview')}><BookOpen size={16} />Overview</button>
        <button className={activeTab === 'knowledge' ? 'active' : ''} onClick={() => setActiveTab('knowledge')}><LayoutTemplate size={16} />Knowledge</button>
        <button className={activeTab === 'developer' ? 'active' : ''} onClick={() => setActiveTab('developer')}><Code size={16} />Developer Data</button>
        <button className={activeTab === 'export' ? 'active' : ''} onClick={() => setActiveTab('export')}><Download size={16} />Export</button>
      </nav>

      {activeTab === 'overview' && (
        <div className="document-content">
          <section className="card document-header-card">
            <div className="document-title-row">
              <div className="document-title-icon"><FileText size={23} /></div>
              <div>
                <p className="eyebrow">Document</p>
                <h1>{document.filename}</h1>
              </div>
            </div>
            <dl className="document-metadata">
              <div><dt>Document type</dt><dd className="type-badge">{documentTypeName}</dd></div>
              <div><dt>Owner</dt><dd>{document.owner || 'Not identified'}</dd></div>
              <div><dt>Uploaded</dt><dd><Calendar size={14} />{formatDate(document.createdAt)}</dd></div>
              <div><dt>Verified confidence</dt><dd className={`match-badge ${document.confidence}`}><CheckCircle size={14} />{document.confidence} confidence</dd></div>
            </dl>
            {document.warnings?.length > 0 && (
              <div className="extraction-warnings">
                <AlertTriangle size={16} />
                <ul>{document.warnings.map((warning) => <li key={warning}>{warning}</li>)}</ul>
              </div>
            )}
          </section>

          {profileFields.length > 0 && (
            <section className="card profile-card">
              <div className="card-heading"><User size={18} /><div><h2>Profile</h2><p>Contact information extracted from this document.</p></div></div>
              <dl className="profile-field-grid">
                {profileFields.map((field) => (
                  <div className="profile-field" key={field.label}>
                    <dt>{field.label === 'Name' ? <User size={16} /> : field.label === 'Email' ? <Mail size={16} /> : field.label === 'Phone' ? <Phone size={16} /> : field.label === 'Location' ? <MapPin size={16} /> : <Globe size={16} />}{field.label}</dt>
                    <dd>{field.href ? <a href={field.href} target={field.label === 'Email' || field.label === 'Phone' ? undefined : '_blank'} rel={field.label === 'Email' || field.label === 'Phone' ? undefined : 'noreferrer'}>{field.value}</a> : field.value}</dd>
                  </div>
                ))}
              </dl>
            </section>
          )}

          <section className="card summary-card">
            <div className="card-heading"><Sparkles size={18} /><div><h2>AI summary</h2><p>Document-level context generated during extraction.</p></div></div>
            {document.ai_summary ? <p className="summary-copy">{document.ai_summary}</p> : <p className="card-empty">No AI summary available.</p>}
          </section>

          <section className="card query-card">
            <div className="card-heading">
              <Search size={18} />
              <div>
                <h2>Ask about this collection</h2>
                <p>Get a grounded answer with source citations — not just a table dump.</p>
              </div>
            </div>

            <form className="document-query-form" onSubmit={submitQuery}>
              <input
                value={query}
                onChange={(event) => setQuery(event.target.value)}
                placeholder="Ask a question about your data…"
                disabled={isQuerying}
              />
              <button className="btn btn-primary" type="submit" disabled={isQuerying || !query.trim()}>
                {isQuerying ? 'Working…' : 'Ask'}
              </button>
            </form>

            <div className="suggested-queries" aria-label="Suggested questions">
              {questionSuggestions.map((suggestion) => (
                <button key={suggestion} type="button" disabled={isQuerying} onClick={() => runSuggestedQuery(suggestion)}>
                  {suggestion}
                </button>
              ))}
            </div>

            <div className="query-refine-bar">
              <button
                type="button"
                className="btn btn-ghost"
                onClick={() => setShowRefine((open) => !open)}
                aria-expanded={showRefine}
              >
                {showRefine ? 'Hide filters' : 'Refine with filters'}
              </button>
              <label className="query-confidence-toggle">
                <input
                  type="checkbox"
                  checked={excludeLowConfidence}
                  onChange={(event) => setExcludeLowConfidence(event.target.checked)}
                  disabled={isQuerying}
                />
                Exclude low-confidence values
              </label>
            </div>

            {showRefine && (
              <form className="structured-filter-form" onSubmit={submitFilter}>
                {filterFields.length === 0 ? (
                  <p className="card-empty">No filterable columns in this collection yet.</p>
                ) : (
                  <>
                    <div className="filter-match-row">
                      <span>Match</span>
                      <select value={match} onChange={(event) => setMatch(event.target.value as 'all' | 'any')} disabled={isQuerying}>
                        <option value="all">all conditions</option>
                        <option value="any">any condition</option>
                      </select>
                      <span className="filter-match-hint">Conditions on the same section must match the same entry when using “all”.</span>
                    </div>

                    <div className="filter-rows" aria-label="Filter conditions">
                      {filterRows.map((row) => {
                        const field = fieldByKey(row.fieldKey);
                        const column = field?.column;
                        const operators = operatorsForColumn(column);
                        const control = valueControlFor(column, row.operator);
                        const options = control === 'closed-select'
                          ? (distinctValues[row.fieldKey] ?? [])
                          : [];
                        const loadingOptions = control === 'closed-select' && distinctLoading[row.fieldKey];
                        return (
                          <div className="filter-row" key={row.id}>
                            <select value={row.fieldKey} onChange={(event) => updateFilterRow(row.id, { fieldKey: event.target.value })} disabled={isQuerying} aria-label="Column">
                              {filterGroups.map(([group, fields]) => (
                                <optgroup key={group} label={group}>
                                  {fields.map((item) => (
                                    <option key={item.key} value={item.key}>{formatLabel(item.column.name)}</option>
                                  ))}
                                </optgroup>
                              ))}
                            </select>
                            <select value={row.operator} onChange={(event) => updateFilterRow(row.id, { operator: event.target.value as FilterOperator })} disabled={isQuerying} aria-label="Operator">
                              {operators.map((op) => (
                                <option key={op.value} value={op.value}>{op.label}</option>
                              ))}
                            </select>
                            {control === 'boolean' ? (
                              <select
                                value={row.value}
                                onChange={(event) => updateFilterRow(row.id, { value: event.target.value })}
                                disabled={isQuerying}
                                aria-label="Value"
                              >
                                <option value="">Select…</option>
                                <option value="true">True</option>
                                <option value="false">False</option>
                              </select>
                            ) : control === 'closed-select' ? (
                              <select
                                value={row.value}
                                onChange={(event) => updateFilterRow(row.id, { value: event.target.value })}
                                disabled={isQuerying || !!loadingOptions}
                                aria-label="Value"
                              >
                                <option value="">{loadingOptions ? 'Loading…' : 'Select…'}</option>
                                {options.map((option) => (
                                  <option key={option} value={option}>{option}</option>
                                ))}
                              </select>
                            ) : control === 'date' || control === 'text' ? (
                              <input
                                type={control === 'date' ? 'date' : 'text'}
                                value={row.value}
                                onChange={(event) => updateFilterRow(row.id, { value: event.target.value })}
                                placeholder={valuePlaceholder(column)}
                                disabled={isQuerying}
                                aria-label="Value"
                                autoComplete="off"
                              />
                            ) : (
                              <span className="filter-value-placeholder" aria-hidden="true" />
                            )}
                            <button
                              type="button"
                              className="btn btn-ghost filter-remove"
                              onClick={() => setFilterRows((rows) => (rows.length === 1 ? rows : rows.filter((item) => item.id !== row.id)))}
                              disabled={isQuerying || filterRows.length === 1}
                              aria-label="Remove condition"
                            >
                              Remove
                            </button>
                          </div>
                        );
                      })}
                    </div>

                    <div className="filter-toolbar">
                      <button
                        type="button"
                        className="btn btn-ghost"
                        onClick={() => setFilterRows((rows) => [...rows, newFilterRow(filterFields)])}
                        disabled={isQuerying}
                      >
                        Add condition
                      </button>
                      <div className="filter-sort">
                        <label>
                          <span>Sort</span>
                          <select value={sortColumn} onChange={(event) => setSortColumn(event.target.value)} disabled={isQuerying}>
                            <option value="">Default</option>
                            {sortableColumns.map((column) => (
                              <option key={column.name} value={column.name}>{formatLabel(column.name)}</option>
                            ))}
                          </select>
                        </label>
                        <select value={sortDirection} onChange={(event) => setSortDirection(event.target.value as 'asc' | 'desc')} disabled={isQuerying || !sortColumn} aria-label="Sort direction">
                          <option value="asc">Ascending</option>
                          <option value="desc">Descending</option>
                        </select>
                      </div>
                      <button className="btn btn-primary" type="submit" disabled={isQuerying}>
                        {isQuerying ? 'Filtering…' : 'Apply filters'}
                      </button>
                    </div>
                  </>
                )}
              </form>
            )}

            {queryResult && (
              <div className="answer-card" role="region" aria-label="Answer">
                {queryResult.answerable === false ? (
                  <div className="answer-refusal">
                    <p className="answer-headline">Not a question about this data</p>
                    <p className="answer-caveat">
                      {queryResult.reason || "That doesn't look like a question about your data — try asking about a specific field, value or document."}
                    </p>
                  </div>
                ) : (
                  <>
                    <p className="answer-eyebrow">
                      {queryResult.answerType ? queryResult.answerType.replace(/_/g, ' ') : 'answer'}
                    </p>
                    <p className="answer-headline">{queryResult.headline || queryResult.summary || 'Done.'}</p>
                    {queryResult.summary && queryResult.summary !== queryResult.headline && (
                      <p className="answer-summary">{queryResult.summary}</p>
                    )}
                    {answerCoverage && <p className="answer-coverage">{answerCoverage}</p>}
                    {queryResult.caveats && queryResult.caveats.length > 0 && (
                      <ul className="answer-caveats">
                        {queryResult.caveats.map((caveat) => (
                          <li key={caveat}>{caveat}</li>
                        ))}
                      </ul>
                    )}

                    {answerRows.length > 0 ? (
                      <div className="answer-evidence">
                        <h3>Supporting rows</h3>
                        <DataTable columns={queryResult.columns} rows={answerRows} showMetadata />
                      </div>
                    ) : (
                      <p className="card-empty">No supporting rows for this answer.</p>
                    )}

                    <details
                      className="answer-how"
                      open={showHowComputed}
                      onToggle={(event) => setShowHowComputed((event.target as HTMLDetailsElement).open)}
                    >
                      <summary>How this was computed <ChevronDown size={16} /></summary>
                      <div className="answer-how-body">
                        {queryResult.explanation && <p>{queryResult.explanation}</p>}
                        {queryResult.generatedSql && (
                          <pre className="answer-sql">{queryResult.generatedSql}</pre>
                        )}
                      </div>
                    </details>
                  </>
                )}
              </div>
            )}
          </section>
        </div>
      )}

      {activeTab === 'knowledge' && (
        <div className="document-content">
          <div className="page-section-heading">
            <div>
              <p className="eyebrow">Detected document</p>
              <h2>{documentTypeName}</h2>
              {documentCategory && <p className="knowledge-document-category">{formatCategory(documentCategory)}</p>}
            </div>
            <p>{sections.length} {sections.length === 1 ? 'section' : 'sections'} detected</p>
          </div>
          {sections.length === 0
            ? (
              <section className="card">
                <p className="card-empty">
                  {document.documentType
                    ? 'This document was successfully extracted, but no semantic sections were identified.'
                    : 'This document was extracted before section detection existed. Re-upload it to see its sections.'}
                </p>
              </section>
            )
            : sections.map(renderSection)}
        </div>
      )}

      {activeTab === 'developer' && (
        <div className="document-content">
          <section className="card developer-card">
            <div className="card-heading"><Code size={18} /><div><h2>Developer data</h2><p>How the extraction pipeline produced this result.</p></div></div>
            <div className="provenance-audit">
              <table>
                <thead>
                  <tr><th>Field</th><th>Value</th><th>Level</th><th>Score</th><th>Page</th><th>Chunk</th><th>Verification notes</th><th>Cited source text</th></tr>
                </thead>
                <tbody>
                  {provenanceEntries.map((entry) => (
                    <tr key={entry.path}>
                      <td>{entry.path}</td>
                      <td>{entry.value}</td>
                      <td>{entry.confidence ?? '—'}</td>
                      <td>{entry.score?.toFixed(2) ?? '—'}</td>
                      <td>{entry.page ?? '—'}</td>
                      <td>{entry.chunk ?? '—'}</td>
                      <td>{entry.note ?? '—'}</td>
                      <td>{entry.rawSource ?? '—'}</td>
                    </tr>
                  ))}
                  {provenanceEntries.length === 0 && <tr><td colSpan={8}>No per-field provenance was recorded for this document.</td></tr>}
                </tbody>
              </table>
            </div>
            <details>
              <summary>Show raw extraction JSON <ChevronDown size={17} /></summary>
              <pre>{JSON.stringify(data, null, 2)}</pre>
            </details>
          </section>
        </div>
      )}

      {activeTab === 'export' && (
        <div className="document-content">
          <section className="card export-card">
            <div className="card-heading"><Download size={18} /><div><h2>Export collection data</h2><p>Download the current collection in a portable format.</p></div></div>
            <ExportMenu collectionId={collection.id} />
          </section>
        </div>
      )}
    </div>
  );
}
