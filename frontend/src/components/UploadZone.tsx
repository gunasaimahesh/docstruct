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

async function filesFromClipboardApi(): Promise<File[]> {
  if (!navigator.clipboard?.read) return [];

  const stamp = Date.now();
  const files: File[] = [];
  const items = await navigator.clipboard.read();

  for (const item of items) {
    const imageType = item.types.find((type) => type.startsWith('image/'));
    if (imageType) {
      const blob = await item.getType(imageType);
      const ext = imageType.split('/')[1]?.replace('jpeg', 'jpg') || 'png';
      files.push(new File([blob], `paste-${stamp}.${ext}`, { type: imageType }));
      continue;
    }
    if (item.types.includes('text/plain')) {
      const blob = await item.getType('text/plain');
      const text = (await blob.text()).trim();
      if (text) {
        files.push(new File([text], `paste-${stamp}.txt`, { type: 'text/plain' }));
      }
    }
  }

  return files;
}

export default function UploadZone({
  onUpload,
  collectionId,
  isUploading,
  uploadStatus,
  compact = false,
}: UploadZoneProps) {
  const [isDragActive, setIsDragActive] = useState(false);
  const [pasteHint, setPasteHint] = useState<string | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const dragCounter = useRef(0);

  const handleFiles = useCallback(
    async (files: FileList | File[] | null) => {
      if (!files || files.length === 0) return;
      const list = Array.from(files);
      setPasteHint(null);

      for (const file of list) {
        await onUpload(file, collectionId);
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
        void handleFiles(e.dataTransfer.files);
      }
    },
    [handleFiles]
  );

  const handleClick = () => {
    if (!isUploading) {
      fileInputRef.current?.click();
    }
  };

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      handleClick();
    }
  };

  const handleFileChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    void handleFiles(e.target.files);
    if (fileInputRef.current) {
      fileInputRef.current.value = '';
    }
  };

  const handlePasteButton = async (e: React.MouseEvent) => {
    e.preventDefault();
    e.stopPropagation();
    if (isUploading) return;

    try {
      const files = await filesFromClipboardApi();
      if (files.length > 0) {
        await handleFiles(files);
        return;
      }
      setPasteHint('Clipboard has no image or text — copy one, then try again');
    } catch {
      setPasteHint('Could not read the clipboard — allow clipboard access and try again');
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
      onKeyDown={handleKeyDown}
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
            Drop or click to add
          </div>
        </>
      ) : (
        <>
          <div className="upload-zone-icon">📁</div>
          <div className="upload-zone-title">
            {isDragActive ? 'Drop your documents here' : 'Drop documents here or click to browse'}
          </div>
          <div className="upload-zone-subtitle">
            Or copy an image / text and use Paste from clipboard
          </div>
          <div className="upload-zone-actions">
            <button
              type="button"
              className="upload-paste-btn"
              onClick={handlePasteButton}
            >
              Paste from clipboard
            </button>
          </div>
          {pasteHint && <p className="upload-paste-hint">{pasteHint}</p>}
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
