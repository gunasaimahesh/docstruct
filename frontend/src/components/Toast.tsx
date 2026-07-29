'use client';

import { useState, useEffect, useCallback } from 'react';

interface Toast {
  id: string;
  type: 'success' | 'error' | 'warning';
  message: string;
}

// Global toast state (simple approach without context)
let globalToasts: Toast[] = [];
let globalSetToasts: React.Dispatch<React.SetStateAction<Toast[]>> | null = null;

export function showToast(type: Toast['type'], message: string) {
  const id = Math.random().toString(36).substring(2);
  const toast: Toast = { id, type, message };

  if (globalSetToasts) {
    globalSetToasts((prev) => [...prev, toast]);
    // Auto-dismiss after 5 seconds
    setTimeout(() => {
      if (globalSetToasts) {
        globalSetToasts((prev) => prev.filter((t) => t.id !== id));
      }
    }, 5000);
  }
}

export default function ToastContainer() {
  const [toasts, setToasts] = useState<Toast[]>([]);

  useEffect(() => {
    globalSetToasts = setToasts;
    return () => {
      globalSetToasts = null;
    };
  }, []);

  const dismiss = useCallback((id: string) => {
    setToasts((prev) => prev.filter((t) => t.id !== id));
  }, []);

  if (toasts.length === 0) return null;

  return (
    <div style={{ position: 'fixed', bottom: '24px', right: '24px', zIndex: 1000, display: 'flex', flexDirection: 'column', gap: '8px' }}>
      {toasts.map((toast) => (
        <div
          key={toast.id}
          className={`toast toast-${toast.type}`}
          onClick={() => dismiss(toast.id)}
          role="alert"
          style={{ cursor: 'pointer' }}
        >
          {toast.type === 'success' && '✓ '}
          {toast.type === 'error' && '✕ '}
          {toast.type === 'warning' && '⚠ '}
          {toast.message}
        </div>
      ))}
    </div>
  );
}
