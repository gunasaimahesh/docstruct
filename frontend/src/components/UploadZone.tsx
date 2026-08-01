'use client';

import { useState, useRef, useCallback } from 'react';

interface UploadZoneProps {
  onUpload: (file: File, collectionId?: string) => Promise<void>;
  collectionId?: string;
  isUploading: boolean;
  /** Honest status line while the request is in flight — not a fake percentage. */
  uploadStatus: string | null;
  compact?: boolean;
}

export default function UploadZone({
  onUpload,
  collectionId,
  isUploading,
  uploadStatus,
  compact = false,
}: UploadZoneProps) {
  const [isDragActive, setIsDragActive] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const dragCounter = useRef(0);

  const handleFiles = useCallback(
    async (files: FileList | null) => {
      if (!files || files.length === 0) return;

      // Process files sequentially
      for (let i = 0; i < files.length; i++) {
        await onUpload(files[i], collectionId);
      }
    },
    [onUpload, collectionId]
  );

  const handleDragEnter = useCallback((e: React.DragEvent) => {
    e.preventDefault();
    e.stopPropagation();
    dragCounter.current++;
    if (e.dataTransfer.items && e.dataTransfer.items.length > 0) {
      setIsDragActive(true);
    }
  }, []);

  const handleDragLeave = useCallback((e: React.DragEvent) => {
    e.preventDefault();
    e.stopPropagation();
    dragCounter.current--;
    if (dragCounter.current === 0) {
      setIsDragActive(false);
    }
  }, []);

  const handleDragOver = useCallback((e: React.DragEvent) => {
    e.preventDefault();
    e.stopPropagation();
  }, []);

  const handleDrop = useCallback(
    (e: React.DragEvent) => {
      e.preventDefault();
      e.stopPropagation();
      setIsDragActive(false);
      dragCounter.current = 0;

      if (e.dataTransfer.files && e.dataTransfer.files.length > 0) {
        handleFiles(e.dataTransfer.files);
      }
    },
    [handleFiles]
  );

  const handleClick = () => {
    if (!isUploading) {
      fileInputRef.current?.click();
    }
  };

  const handleFileChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    handleFiles(e.target.files);
    // Reset input so same file can be uploaded again
    if (fileInputRef.current) {
      fileInputRef.current.value = '';
    }
  };

  if (isUploading && uploadStatus) {
    const isDone = uploadStatus === 'Done!';
    return (
      <div className={`upload-zone ${compact ? 'compact' : ''}`} style={compact ? { padding: '24px' } : undefined}>
        <div className="upload-progress">
          {!compact && !isDone && <div className="spinner spinner-lg" style={{ marginBottom: '16px' }} />}
          {compact && <div className="spinner" />}
          <div className="upload-progress-step">{uploadStatus}</div>
          {!isDone && (
            <>
              <div className="upload-progress-bar-wrapper">
                <div className="upload-progress-bar upload-progress-bar-indeterminate" />
              </div>
              <div className="upload-progress-text">
                Often takes 15–60 seconds — the AI is reading the document
              </div>
            </>
          )}
        </div>
      </div>
    );
  }

  return (
    <div
      className={`upload-zone ${isDragActive ? 'drag-active' : ''} ${compact ? 'compact' : ''}`}
      onDragEnter={handleDragEnter}
      onDragLeave={handleDragLeave}
      onDragOver={handleDragOver}
      onDrop={handleDrop}
      onClick={handleClick}
      style={compact ? { padding: '24px' } : undefined}
      role="button"
      tabIndex={0}
      aria-label="Upload document"
    >
      <input
        ref={fileInputRef}
        type="file"
        accept=".pdf,.csv,.tsv,.txt,.text,.md,.log,.png,.jpg,.jpeg,.webp,.tiff,.tif"
        onChange={handleFileChange}
        multiple
      />

      {compact ? (
        <>
          <div className="upload-zone-icon">📄</div>
          <div className="upload-zone-title" style={{ fontSize: 'var(--text-base)' }}>
            Add more documents
          </div>
          <div className="upload-zone-subtitle">
            Drop files here or click to browse
          </div>
        </>
      ) : (
        <>
          <div className="upload-zone-icon">📁</div>
          <div className="upload-zone-title">
            {isDragActive ? 'Drop your documents here' : 'Drop documents here, or click to browse'}
          </div>
          <div className="upload-zone-subtitle">
            Upload messy documents and get clean, structured data instantly
          </div>
          <div className="upload-zone-formats">
            <span className="format-badge">PDF</span>
            <span className="format-badge">PNG</span>
            <span className="format-badge">JPG</span>
            <span className="format-badge">CSV</span>
            <span className="format-badge">TXT</span>
          </div>
        </>
      )}
    </div>
  );
}
