'use client';

import type { ConfidenceLevel } from '@/types';

interface ConfidenceBadgeProps {
  level: ConfidenceLevel;
  showLabel?: boolean;
  /** Deterministic 0–1 verification score, shown on hover when available */
  score?: number;
}

export default function ConfidenceBadge({ level, showLabel = true, score }: ConfidenceBadgeProps) {
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

  const title = score === undefined
    ? `${labels[level]} confidence`
    : `${labels[level]} confidence · verification score ${score.toFixed(2)}`;

  return (
    <span className={`confidence-badge ${level}`} title={title}>
      <span className={`confidence-dot ${level}`} />
      {showLabel && <span>{icons[level]} {labels[level]}</span>}
    </span>
  );
}
