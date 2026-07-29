'use client';

import { useState } from 'react';
import type { Collection, Document, ExtractionRow } from '@/types';
import {
  Award,
  BookOpen,
  Briefcase,
  Calendar,
  CheckCircle,
  ChevronDown,
  Code,
  Download,
  FileText,
  GraduationCap,
  LayoutTemplate,
  Mail,
  MapPin,
  Phone,
  Search,
  Sparkles,
  User,
  Wrench,
  Globe,
} from 'lucide-react';
import DataTable from './DataTable';
import ExportMenu from './ExportMenu';
import { transformJsonToViewModel, type ViewEntity, type ViewField } from '@/lib/view-model';

interface QueryResult {
  columns: string[];
  rows: Record<string, unknown>[];
  rowCount: number;
  generatedSql: string;
  explanation: string;
  summary?: string;
}

interface DocumentViewerProps {
  collection: Collection;
  document: Document;
  data: Record<string, unknown>;
  onQuery: (query: string) => Promise<void>;
  isQuerying: boolean;
  queryResult: QueryResult | null;
}

type ViewTab = 'overview' | 'knowledge' | 'developer' | 'export';

type ProfileField = {
  label: 'Name' | 'Email' | 'Phone' | 'Location' | 'LinkedIn' | 'GitHub' | 'Portfolio';
  value: string;
  href?: string;
};

function getSectionIcon(name: string) {
  const normalised = name.toLowerCase();
  if (normalised.includes('contact') || normalised.includes('profile') || normalised.includes('name')) return <User size={17} />;
  if (normalised.includes('experience') || normalised.includes('job') || normalised.includes('work')) return <Briefcase size={17} />;
  if (normalised.includes('education') || normalised.includes('school')) return <GraduationCap size={17} />;
  if (normalised.includes('skill')) return <Wrench size={17} />;
  if (normalised.includes('achievement') || normalised.includes('award') || normalised.includes('publication')) return <Award size={17} />;
  return <LayoutTemplate size={17} />;
}

function formatLabel(value: string) {
  return value.replace(/_/g, ' ');
}

/** Section titles drop redundant structural suffixes like "Array" or "List". */
function formatSectionLabel(value: string) {
  return formatLabel(value).replace(/\s+(array|list)$/i, '').trim() || formatLabel(value);
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

function getProfileFields(data: Record<string, unknown>): ProfileField[] {
  const definitions: Array<{ label: ProfileField['label']; aliases: string[]; prefix?: string }> = [
    { label: 'Name', aliases: ['candidate_name', 'full_name', 'name'] },
    { label: 'Email', aliases: ['email', 'email_address'], prefix: 'mailto:' },
    { label: 'Phone', aliases: ['phone', 'phone_number', 'mobile', 'mobile_number'], prefix: 'tel:' },
    { label: 'Location', aliases: ['location', 'address', 'city'] },
    { label: 'LinkedIn', aliases: ['linkedin', 'linkedin_profile', 'linkedin_url'] },
    { label: 'GitHub', aliases: ['github', 'github_profile', 'github_url'] },
    { label: 'Portfolio', aliases: ['portfolio', 'portfolio_url', 'website', 'personal_website'] },
  ];

  const contactSources = Object.entries(data)
    .filter(([key]) => /contact|profile|candidate|person/i.test(key))
    .map(([, value]) => value);

  return definitions.flatMap((definition) => {
    const value = findTopLevelProfileValue(data, definition.aliases)
      || contactSources.map((source) => findProfileValue(source, definition.aliases)).find(Boolean);
    if (!value) return [];
    const isLink = definition.label === 'Email' || definition.label === 'Phone' || ['LinkedIn', 'GitHub', 'Portfolio'].includes(definition.label);
    return [{ label: definition.label, value, href: isLink ? makeLink(value, definition.prefix) : undefined }];
  });
}

export default function DocumentViewer({ collection, document, data, onQuery, isQuerying, queryResult }: DocumentViewerProps) {
  const [activeTab, setActiveTab] = useState<ViewTab>('overview');
  const [query, setQuery] = useState('');

  const rootEntity = transformJsonToViewModel(collection.schema, [data as ExtractionRow], collection.name);
  const sections = rootEntity.rows[0]?.children ? Object.entries(rootEntity.rows[0].children) : [];
  const profileFields = getProfileFields(data);

  const getSectionType = (sectionName: string) => collection.schema.columns.find((column) => column.name === sectionName)?.type || 'entity_array';

  const submitQuery = (event: React.FormEvent) => {
    event.preventDefault();
    if (query.trim() && !isQuerying) onQuery(query.trim());
  };

  const runSuggestedQuery = (suggestion: string) => {
    setQuery(suggestion);
    onQuery(suggestion);
  };

  const renderFieldValue = (field: ViewField) => {
    if (field.value === null || field.value === undefined || field.value === '') return <span className="empty-value">Not available</span>;
    const value = String(field.value);
    if (value.startsWith('http')) return <a href={value} target="_blank" rel="noreferrer">{value}</a>;
    if (value.includes('@') && !value.includes(' ')) return <a href={`mailto:${value}`}>{value}</a>;
    return value;
  };

  const renderObjectSection = (entity: ViewEntity) => {
    const row = entity.rows[0];
    if (!row) return <p className="card-empty">No extracted data is available for this section.</p>;

    return (
      <dl className="entity-field-grid">
        {entity.columns.map((column) => (
          <div key={column} className="entity-field">
            <dt>{formatLabel(column)}</dt>
            <dd>{renderFieldValue(row.fields[column] || { value: null })}</dd>
          </div>
        ))}
      </dl>
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
              <div><dt>Document type</dt><dd className="type-badge">{formatLabel(collection.documentType)}</dd></div>
              <div><dt>Owner</dt><dd>{document.owner || 'Not identified'}</dd></div>
              <div><dt>Uploaded</dt><dd><Calendar size={14} />{formatDate(document.createdAt)}</dd></div>
              <div><dt>Match score</dt><dd className={`match-badge ${document.confidence}`}><CheckCircle size={14} />{document.confidence} confidence</dd></div>
            </dl>
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
            <div className="card-heading"><Search size={18} /><div><h2>Ask the document</h2><p>Use natural language to inspect the extracted data.</p></div></div>
            <form className="document-query-form" onSubmit={submitQuery}>
              <input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="Ask questions about this document..." disabled={isQuerying} />
              <button className="btn btn-primary" type="submit" disabled={isQuerying || !query.trim()}>{isQuerying ? 'Searching…' : 'Ask'}</button>
            </form>
            <div className="suggested-queries" aria-label="Suggested queries">
              {['Summarise experience', 'Extract all emails', 'List skills'].map((suggestion) => <button key={suggestion} type="button" onClick={() => runSuggestedQuery(suggestion)}>{suggestion}</button>)}
            </div>
            {queryResult && (
              <div className="query-result">
                <h3>AI response</h3>
                {queryResult.summary && <p>{queryResult.summary}</p>}
                {queryResult.rows.length > 0 ? <DataTable columns={queryResult.columns} rows={queryResult.rows.map((row, index) => ({ id: `query-${index}`, fields: Object.fromEntries(queryResult.columns.map((column) => [column, { value: row[column] }])) }))} showMetadata={false} /> : <p className="card-empty">The query completed without tabular results.</p>}
              </div>
            )}
          </section>
        </div>
      )}

      {activeTab === 'knowledge' && (
        <div className="document-content">
          <div className="page-section-heading"><div><p className="eyebrow">Extracted data</p><h2>Knowledge</h2></div><p>{sections.length} {sections.length === 1 ? 'section' : 'sections'} detected</p></div>
          {sections.length === 0 ? <section className="card"><p className="card-empty">No knowledge sections are available for this document.</p></section> : sections.map(([name, entity]) => {
            const type = getSectionType(name);
            const count = type === 'object' ? 1 : entity.rows.length;
            return (
              <details className="knowledge-section-card" key={name}>
                <summary>
                  <span className="section-icon">{getSectionIcon(name)}</span>
                  <span className="section-summary-text"><strong>{formatSectionLabel(name)}</strong><small>{count} {count === 1 ? 'entity' : 'entities'}</small></span>
                  <ChevronDown className="summary-chevron" size={18} />
                </summary>
                <div className="knowledge-section-body">
                  {count === 0 ? <p className="card-empty">No extracted entities are available in this section.</p> : type === 'object' ? renderObjectSection(entity) : <DataTable columns={entity.columns} rows={entity.rows} showMetadata />}
                </div>
              </details>
            );
          })}
        </div>
      )}

      {activeTab === 'developer' && (
        <div className="document-content">
          <section className="card developer-card">
            <div className="card-heading"><Code size={18} /><div><h2>Developer data</h2><p>Raw extraction output and provenance for debugging.</p></div></div>
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
