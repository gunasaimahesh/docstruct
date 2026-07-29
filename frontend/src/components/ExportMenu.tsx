'use client';

import { useState, useRef, useEffect } from 'react';

interface ExportMenuProps {
  collectionId: string;
}

export default function ExportMenu({ collectionId }: ExportMenuProps) {
  const [isOpen, setIsOpen] = useState(false);
  const menuRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    function handleClickOutside(event: MouseEvent) {
      if (menuRef.current && !menuRef.current.contains(event.target as Node)) {
        setIsOpen(false);
      }
    }
    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, []);

  const handleExport = (format: 'csv' | 'json') => {
    const url = `/api/collections/${collectionId}/export?format=${format}`;
    window.open(url, '_blank');
    setIsOpen(false);
  };

  return (
    <div className="export-menu" ref={menuRef}>
      <button
        className="btn btn-secondary"
        onClick={() => setIsOpen(!isOpen)}
        aria-expanded={isOpen}
        aria-haspopup="true"
      >
        📥 Export
      </button>

      {isOpen && (
        <div className="export-dropdown" role="menu">
          <button
            className="export-dropdown-item"
            onClick={() => handleExport('csv')}
            role="menuitem"
          >
            📊 Export as CSV
          </button>
          <button
            className="export-dropdown-item"
            onClick={() => handleExport('json')}
            role="menuitem"
          >
            📋 Export as JSON
          </button>
        </div>
      )}
    </div>
  );
}
