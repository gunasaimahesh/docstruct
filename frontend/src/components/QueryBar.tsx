'use client';

import { useState, useCallback } from 'react';

interface QueryBarProps {
  onQuery: (query: string) => Promise<void>;
  isQuerying: boolean;
  placeholder?: string;
}

export default function QueryBar({ onQuery, isQuerying, placeholder }: QueryBarProps) {
  const [query, setQuery] = useState('');

  const handleSubmit = useCallback(
    async (e: React.FormEvent) => {
      e.preventDefault();
      if (!query.trim() || isQuerying) return;
      await onQuery(query.trim());
    },
    [query, isQuerying, onQuery]
  );

  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent) => {
      if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        if (query.trim() && !isQuerying) {
          onQuery(query.trim());
        }
      }
    },
    [query, isQuerying, onQuery]
  );

  return (
    <form className="query-bar" onSubmit={handleSubmit}>
      <input
        type="text"
        className="query-input"
        value={query}
        onChange={(e) => setQuery(e.target.value)}
        onKeyDown={handleKeyDown}
        placeholder={placeholder || 'Ask a question about your data... (e.g., "show all invoices over $500")'}
        disabled={isQuerying}
      />
      <button
        type="submit"
        className="btn btn-primary"
        disabled={isQuerying || !query.trim()}
      >
        {isQuerying ? (
          <>
            <span className="spinner" />
            Querying...
          </>
        ) : (
          <>🔍 Query</>
        )}
      </button>
    </form>
  );
}
