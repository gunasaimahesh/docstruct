'use client';

import { useCallback, useMemo, useState } from 'react';
import type { ViewField, ViewRow } from '@/lib/view-model';
import { ArrowDown, ArrowUp, ArrowUpDown, ChevronLeft, ChevronRight, Search } from 'lucide-react';
import ConfidenceBadge from './ConfidenceBadge';
import ProvenancePopover from './ProvenancePopover';
import { isMissingValue, pageLabel } from '@/lib/provenance';

interface DataTableProps {
  columns: string[];
  rows: ViewRow[];
  onCellEdit?: (rowId: string, column: string, value: string) => void;
  showMetadata?: boolean;
}

type SortConfig = { key: string; direction: 'asc' | 'desc' } | null;

function formatColumnName(column: string) {
  return column.replace(/_/g, ' ');
}

function renderFieldValue(field: ViewField) {
  // An absent value is stated as absent. Rendering it as a dash makes a field the
  // document never contained look the same as one the extraction simply skipped.
  if (isMissingValue(field)) return 'Not found in document';
  if (Array.isArray(field.value)) return `${field.value.length} items`;
  if (typeof field.value === 'object') return JSON.stringify(field.value);
  return String(field.value);
}

export default function DataTable({ columns, rows, onCellEdit, showMetadata = true }: DataTableProps) {
  const [editingCell, setEditingCell] = useState<{ rowId: string; column: string } | null>(null);
  const [editValue, setEditValue] = useState('');
  const [searchTerm, setSearchTerm] = useState('');
  const [sortConfig, setSortConfig] = useState<SortConfig>(null);
  const [currentPage, setCurrentPage] = useState(1);
  const rowsPerPage = 10;

  const processedRows = useMemo(() => {
    let result = [...rows];
    if (searchTerm) {
      const query = searchTerm.toLowerCase();
      result = result.filter((row) => columns.some((column) => String(row.fields[column]?.value ?? '').toLowerCase().includes(query)));
    }
    if (sortConfig) {
      result.sort((left, right) => {
        const a = left.fields[sortConfig.key]?.value;
        const b = right.fields[sortConfig.key]?.value;
        if (a === b) return 0;
        if (a === null || a === undefined) return sortConfig.direction === 'asc' ? -1 : 1;
        if (b === null || b === undefined) return sortConfig.direction === 'asc' ? 1 : -1;
        const result = String(a).localeCompare(String(b), undefined, { numeric: true });
        return sortConfig.direction === 'asc' ? result : -result;
      });
    }
    return result;
  }, [columns, rows, searchTerm, sortConfig]);

  const totalPages = Math.max(1, Math.ceil(processedRows.length / rowsPerPage));
  const currentRows = processedRows.slice((currentPage - 1) * rowsPerPage, currentPage * rowsPerPage);
  const lowConfidenceCount = useMemo(
    () => rows.reduce((total, row) => total + columns.filter((column) => row.fields[column]?.confidence === 'low' && !isMissingValue(row.fields[column])).length, 0),
    [columns, rows]
  );

  const sort = (column: string) => {
    setCurrentPage(1);
    setSortConfig((current) => {
      if (!current || current.key !== column) return { key: column, direction: 'asc' };
      return current.direction === 'asc' ? { key: column, direction: 'desc' } : null;
    });
  };

  const commitEdit = useCallback(() => {
    if (editingCell && onCellEdit) onCellEdit(editingCell.rowId, editingCell.column, editValue);
    setEditingCell(null);
  }, [editValue, editingCell, onCellEdit]);

  if (rows.length === 0) return <p className="card-empty" style={{ padding: '16px' }}>No data is available for this section.</p>;

  return (
    <div className="data-table-shell">
      <div className="data-table-controls">
        <div className="data-table-search"><Search size={15} /><input value={searchTerm} onChange={(event) => { setSearchTerm(event.target.value); setCurrentPage(1); }} placeholder="Search data…" /></div>
        <span className="data-table-count">
          {showMetadata && lowConfidenceCount > 0 && <span className="review-count" title="Values that could not be fully verified against the document">{lowConfidenceCount} to review</span>}
          {processedRows.length} {processedRows.length === 1 ? 'row' : 'rows'}
        </span>
      </div>
      <div className="data-table-scroll">
        <table className="data-table">
          <thead><tr><th>#</th>{columns.map((column) => <th key={column}><button onClick={() => sort(column)}>{formatColumnName(column)} {sortConfig?.key === column ? sortConfig.direction === 'asc' ? <ArrowUp size={14} /> : <ArrowDown size={14} /> : <ArrowUpDown size={14} />}</button></th>)}</tr></thead>
          <tbody>
            {currentRows.map((row, index) => <tr key={row.id}>
              <td>{(currentPage - 1) * rowsPerPage + index + 1}</td>
              {columns.map((column) => {
                const field = row.fields[column] || { value: null };
                const canEdit = Boolean(onCellEdit && !Array.isArray(field.value) && typeof field.value !== 'object');
                const isEditing = editingCell?.rowId === row.id && editingCell.column === column;
                const page = pageLabel(field);
                const flagged = showMetadata && field.confidence === 'low';
                return <td key={column} className={flagged ? 'cell-flagged' : undefined} onClick={() => { if (canEdit) { setEditingCell({ rowId: row.id, column }); setEditValue(String(field.value ?? '')); } }}>
                  {isEditing ? <input className="data-table-edit" value={editValue} onChange={(event) => setEditValue(event.target.value)} onBlur={commitEdit} onKeyDown={(event) => { if (event.key === 'Enter') commitEdit(); if (event.key === 'Escape') setEditingCell(null); }} autoFocus /> : <div className="data-table-cell">
                    <span className={isMissingValue(field) ? 'empty-value' : undefined} title={renderFieldValue(field)}>{renderFieldValue(field)}</span>
                    {showMetadata && <span className="cell-provenance">
                      {page && <span className="citation-chip" title={`Read from ${page.toLowerCase()} of the document`}>{page}</span>}
                      {field.confidence && <ConfidenceBadge level={field.confidence} showLabel={false} score={field.evidence?.score} />}
                      <ProvenancePopover label={formatColumnName(column)} field={field} />
                    </span>}
                  </div>}
                </td>;
              })}
            </tr>)}
            {currentRows.length === 0 && <tr><td colSpan={columns.length + 1}>No matching results found.</td></tr>}
          </tbody>
        </table>
      </div>
      {totalPages > 1 && <div className="data-table-pagination"><span>Showing {(currentPage - 1) * rowsPerPage + 1}–{Math.min(currentPage * rowsPerPage, processedRows.length)} of {processedRows.length}</span><div className="pagination-actions"><button className="pagination-button" aria-label="Previous page" disabled={currentPage === 1} onClick={() => setCurrentPage((page) => page - 1)}><ChevronLeft size={17} /></button><button className="pagination-button" aria-label="Next page" disabled={currentPage === totalPages} onClick={() => setCurrentPage((page) => page + 1)}><ChevronRight size={17} /></button></div></div>}
    </div>
  );
}
