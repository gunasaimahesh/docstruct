'use client';

import { useState, useEffect, useCallback } from 'react';
import { useParams, useRouter } from 'next/navigation';
import UploadZone from '@/components/UploadZone';
import DocumentViewer from '@/components/DocumentViewer';
import { showToast } from '@/components/Toast';
import type { Collection, Document, FilterRequest, QueryResult } from '@/types';

export default function CollectionDetailPage() {
  const params = useParams();
  const router = useRouter();
  const collectionId = params.id as string;

  const [collection, setCollection] = useState<Collection | null>(null);
  const [documents, setDocuments] = useState<Document[]>([]);
  const [loading, setLoading] = useState(true);
  const [selectedDocument, setSelectedDocument] = useState<Document | null>(null);

  // Upload state
  const [isUploading, setIsUploading] = useState(false);
  const [uploadStatus, setUploadStatus] = useState<string | null>(null);

  // Query state
  const [isQuerying, setIsQuerying] = useState(false);
  const [queryResult, setQueryResult] = useState<QueryResult | null>(null);

  const fetchCollection = useCallback(async () => {
    try {
      const res = await fetch(`/api/collections/${collectionId}`);
      const result = await res.json();
      if (result.success) {
        setCollection(result.collection);
        setDocuments(result.documents);
      } else {
        showToast('error', result.error || 'Collection not found');
        router.push('/collections');
      }
    } catch {
      showToast('error', 'Failed to load collection');
    } finally {
      setLoading(false);
    }
  }, [collectionId, router]);

  useEffect(() => {
    const loadCollection = async () => {
      await fetchCollection();
    };
    void loadCollection();
  }, [fetchCollection]);

  const handleUpload = useCallback(
    async (file: File) => {
      setIsUploading(true);
      setUploadStatus('Extracting with AI…');

      try {
        const formData = new FormData();
        formData.append('file', file);

        const response = await fetch(`/api/collections/${collectionId}/documents`, {
          method: 'POST',
          body: formData,
        });

        const raw = await response.text();
        let result: { success?: boolean; error?: string; extraction?: { rowCount: number } };
        try {
          result = raw ? JSON.parse(raw) : {};
        } catch {
          throw new Error(
            response.ok
              ? 'Upload failed: server returned a non-JSON response'
              : `Upload failed (${response.status}): ${raw.slice(0, 160) || response.statusText}`,
          );
        }

        if (!response.ok || !result.success) {
          throw new Error(result.error || 'Upload failed');
        }

        setUploadStatus('Done!');
        showToast('success', `Added ${result.extraction.rowCount} rows from "${file.name}"`);

        setTimeout(() => {
          fetchCollection();
          setIsUploading(false);
          setUploadStatus(null);
        }, 400);
      } catch (error) {
        const message = error instanceof Error ? error.message : 'Upload failed';
        showToast('error', message);
        setIsUploading(false);
        setUploadStatus(null);
      }
    },
    [collectionId, fetchCollection]
  );

  const handleQuery = useCallback(
    async (query: string, options?: { excludeLowConfidence?: boolean }) => {
      setIsQuerying(true);
      setQueryResult(null);

      try {
        const res = await fetch(`/api/collections/${collectionId}/query`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            query,
            excludeLowConfidence: options?.excludeLowConfidence || undefined,
          }),
        });

        const result = await res.json();

        if (!res.ok || !result.success) {
          throw new Error(result.error || 'Query failed');
        }

        setQueryResult(result.result);
      } catch (error) {
        const message = error instanceof Error ? error.message : 'Query failed';
        showToast('error', message);
      } finally {
        setIsQuerying(false);
      }
    },
    [collectionId]
  );

  const handleFilter = useCallback(
    async (request: FilterRequest) => {
      setIsQuerying(true);
      setQueryResult(null);

      try {
        const res = await fetch(`/api/collections/${collectionId}/filter`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(request),
        });

        const result = await res.json();

        if (!res.ok || !result.success) {
          throw new Error(result.error || 'Filter failed');
        }

        setQueryResult(result.result);
      } catch (error) {
        const message = error instanceof Error ? error.message : 'Filter failed';
        showToast('error', message);
      } finally {
        setIsQuerying(false);
      }
    },
    [collectionId]
  );

  if (loading) {
    return (
      <div className="page-container page-loading">
        <div className="spinner spinner-lg" />
      </div>
    );
  }

  if (!collection) return null;

  // Documents are returned newest first; preserve an explicit selection when present.
  const currentDocument = selectedDocument || documents[0] || null;

  return (
    <div className="collection-detail-page">
      <div className="collection-detail-toolbar">
        <button className="back-link" onClick={() => router.push('/collections')}>← Collections</button>
        <div className="collection-detail-title">
          <span className="eyebrow">Collection</span>
          <strong>{collection.name}</strong>
        </div>
        <div className="collection-detail-actions">
          {documents.length > 1 && (
            <label className="document-selector">
              <span>Viewing</span>
              <select 
                value={currentDocument?.id || ''}
                onChange={(e) => {
                  const doc = documents.find(d => d.id === e.target.value);
                  if (doc) {
                    setSelectedDocument(doc);
                    setQueryResult(null); // Reset query on doc switch
                  }
                }}
              >
                {documents.map(doc => (
                  <option key={doc.id} value={doc.id}>{doc.filename}</option>
                ))}
              </select>
            </label>
          )}
        </div>
        {documents.length > 0 && <UploadZone onUpload={handleUpload} collectionId={collectionId} isUploading={isUploading} uploadStatus={uploadStatus} compact />}
      </div>

      {documents.length === 0 ? (
        <div className="page-container empty-document-page">
          <section className="card empty-upload-card">
            <h1>Upload a document</h1>
            <p>
              Add a document to extract knowledge using the {collection.documentType.replace(/_/g, ' ')} schema.
            </p>
            <UploadZone
              onUpload={handleUpload}
              collectionId={collectionId}
              isUploading={isUploading}
              uploadStatus={uploadStatus}
            />
          </section>
        </div>
      ) : (
        currentDocument && (
          <DocumentViewer
            collection={collection}
            document={currentDocument}
            data={currentDocument.rawJson?.[0] || {}}
            onQuery={handleQuery}
            onFilter={handleFilter}
            isQuerying={isQuerying}
            queryResult={queryResult}
          />
        )
      )}
    </div>
  );
}
