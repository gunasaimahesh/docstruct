'use client';

import type { ConfidenceLevel } from '@/types';

interface ConfidenceBadgeProps {
  level: ConfidenceLevel;
  showLabel?: boolean;
}

export default function ConfidenceBadge({ level, showLabel = true }: ConfidenceBadgeProps) {
  const labels = {
    high: 'High',
    medium: 'Medium',
    low: 'Low',
  };

  const icons = {
    high: '✓',
    medium: '~',
    low: '!',
  };

  return (
    <span className={`confidence-badge ${level}`}>
      <span className={`confidence-dot ${level}`} />
      {showLabel && <span>{icons[level]} {labels[level]}</span>}
    </span>
  );
}
