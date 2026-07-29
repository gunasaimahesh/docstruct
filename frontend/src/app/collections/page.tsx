'use client';

import { useState, useEffect } from 'react';
import { useRouter } from 'next/navigation';
import type { Collection } from '@/types';
import { showToast } from '@/components/Toast';

export default function CollectionsPage() {
  const router = useRouter();
  const [collections, setCollections] = useState<Collection[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    fetchCollections();
  }, []);

  async function fetchCollections() {
    try {
      const res = await fetch('/api/collections');
      const data = await res.json();
      if (data.success) {
        setCollections(data.collections);
      }
    } catch (error) {
      showToast('error', 'Failed to load collections');
    } finally {
      setLoading(false);
    }
  }

  async function handleDelete(e: React.MouseEvent, id: string, name: string) {
    e.stopPropagation();
    if (!confirm(`Delete collection "${name}"? This will remove all extracted data.`)) return;

    try {
      const res = await fetch(`/api/collections/${id}`, { method: 'DELETE' });
      const data = await res.json();
      if (data.success) {
        showToast('success', `Deleted "${name}"`);
        setCollections(prev => prev.filter(c => c.id !== id));
      } else {
        throw new Error(data.error);
      }
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Delete failed';
      showToast('error', message);
    }
  }

  if (loading) {
    return (
      <div className="page-container">
        <div style={{ display: 'flex', justifyContent: 'center', padding: 'var(--space-4xl)' }}>
          <div className="spinner spinner-lg" />
        </div>
      </div>
    );
  }

  return (
    <div className="page-container">
      <div className="fade-in">
        <h1 className="page-title">Collections</h1>
        <p className="page-subtitle">Your structured document collections</p>
      </div>

      {collections.length === 0 ? (
        <div className="empty-state fade-in fade-in-delay-1">
          <div className="empty-state-icon">📂</div>
          <div className="empty-state-title">No collections yet</div>
          <div className="empty-state-description">
            Upload your first document to create a collection. Each collection groups similar documents and lets you query across all of them.
          </div>
          <button className="btn btn-primary" onClick={() => router.push('/')}>
            📄 Upload a Document
          </button>
        </div>
      ) : (
        <div className="collections-grid fade-in fade-in-delay-1">
          {collections.map((collection) => (
            <div
              key={collection.id}
              className="card card-clickable collection-card"
              onClick={() => router.push(`/collections/${collection.id}`)}
            >
              <div className="collection-card-header">
                <span className="collection-card-type">
                  {collection.documentType.replace(/_/g, ' ')}
                </span>
                <button
                  className="btn btn-ghost btn-icon"
                  onClick={(e) => handleDelete(e, collection.id, collection.name)}
                  title="Delete collection"
                  style={{ fontSize: '14px', color: 'var(--color-text-tertiary)' }}
                >
                  🗑
                </button>
              </div>

              <div className="collection-card-title">{collection.name}</div>

              <div className="collection-card-meta">
                <span className="collection-card-meta-item">
                  📄 {collection.documentCount} {collection.documentCount === 1 ? 'document' : 'documents'}
                </span>
                <span className="collection-card-meta-item">
                  📊 {collection.rowCount} {collection.rowCount === 1 ? 'row' : 'rows'}
                </span>
              </div>

              {collection.schema?.columns && (
                <div className="collection-card-schema">
                  {collection.schema.columns.slice(0, 5).map((col) => (
                    <span key={col.name} className="schema-column-badge">
                      {col.name}
                    </span>
                  ))}
                  {collection.schema.columns.length > 5 && (
                    <span className="schema-column-badge">
                      +{collection.schema.columns.length - 5} more
                    </span>
                  )}
                </div>
              )}

              <div style={{
                fontSize: 'var(--text-xs)',
                color: 'var(--color-text-muted)',
                marginTop: 'var(--space-md)',
              }}>
                Updated {formatDate(collection.updatedAt)}
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

function formatDate(dateStr: string): string {
  try {
    const date = new Date(dateStr);
    const now = new Date();
    const diffMs = now.getTime() - date.getTime();
    const diffMins = Math.floor(diffMs / 60000);

    if (diffMins < 1) return 'just now';
    if (diffMins < 60) return `${diffMins}m ago`;
    if (diffMins < 1440) return `${Math.floor(diffMins / 60)}h ago`;
    return date.toLocaleDateString();
  } catch {
    return dateStr;
  }
}
