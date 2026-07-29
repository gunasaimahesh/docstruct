'use client';

import { useEffect, useRef, useState } from 'react';
import { AlertTriangle, CheckCircle, HelpCircle, Info, MinusCircle } from 'lucide-react';
import type { ViewField } from '@/lib/view-model';
import {
  SNIPPET_LIMIT,
  confidenceLabel,
  pageLabel,
  provenanceStatus,
  reviewReason,
  sourceSnippet,
  statusLabel,
  type ProvenanceStatus,
} from '@/lib/provenance';

interface ProvenancePopoverProps {
  /** Field name as the reader sees it in the table or list. */
  label: string;
  field: ViewField;
}

const CARD_WIDTH = 300;
const CARD_HEIGHT = 280;

function StatusIcon({ status }: { status: ProvenanceStatus }) {
  if (status === 'verified') return <CheckCircle size={14} />;
  if (status === 'review') return <AlertTriangle size={14} />;
  if (status === 'absent') return <MinusCircle size={14} />;
  return <HelpCircle size={14} />;
}

/**
 * "Where did this value come from?" for one extracted field.
 *
 * Self-contained on purpose: each button owns its own card and its own
 * outside-click listener, so opening one card closes any other without the
 * cells having to coordinate through shared state.
 */
export default function ProvenancePopover({ label, field }: ProvenancePopoverProps) {
  const [anchor, setAnchor] = useState<DOMRect | null>(null);
  const [expanded, setExpanded] = useState(false);
  const cardRef = useRef<HTMLDivElement | null>(null);

  // Registered only while the card is open, so the click that opened it has
  // already finished dispatching. A permanent listener would close the card
  // during that same click: the App Router hydrates the whole document, so
  // React's delegated handler and this one sit on the same node, where
  // stopPropagation() cannot stop a sibling listener.
  useEffect(() => {
    if (!anchor) return;
    const close = (event: MouseEvent) => {
      if (!cardRef.current?.contains(event.target as Node)) setAnchor(null);
    };
    document.addEventListener('click', close);
    return () => document.removeEventListener('click', close);
  }, [anchor]);

  const toggle = (event: React.MouseEvent<HTMLButtonElement>) => {
    // Cells can be click-to-edit; asking where a value came from is not an edit.
    event.stopPropagation();
    const rect = event.currentTarget.getBoundingClientRect();
    setExpanded(false);
    setAnchor((current) => (current ? null : rect));
  };

  const status = provenanceStatus(field);
  const confidence = confidenceLabel(field);
  const page = pageLabel(field);
  const snippet = sourceSnippet(field);
  const reason = reviewReason(field);
  const isLongSnippet = Boolean(snippet && snippet.length > SNIPPET_LIMIT);
  const shownSnippet = snippet && isLongSnippet && !expanded
    ? `${snippet.slice(0, SNIPPET_LIMIT).trimEnd()}…`
    : snippet;

  return (
    <>
      <button className="metadata-button" aria-label={`Where "${label}" came from`} aria-expanded={Boolean(anchor)} onClick={toggle}>
        <Info size={15} />
      </button>

      {anchor && (
        <div
          ref={cardRef}
          className="provenance-card"
          style={{
            top: Math.min(anchor.bottom + 8, window.innerHeight - CARD_HEIGHT),
            left: Math.min(anchor.left, window.innerWidth - CARD_WIDTH - 4),
          }}
        >
          <p className="provenance-card-title">{label}</p>
          <p className={`provenance-status ${status}`}><StatusIcon status={status} />{statusLabel(status)}</p>

          <dl className="provenance-rows">
            {confidence && <div><dt>Confidence</dt><dd>{confidence}</dd></div>}
            {page && <div><dt>Found on</dt><dd>{page}</dd></div>}
            {snippet && (
              <div>
                <dt>Source</dt>
                <dd>
                  <span className="provenance-quote" title={snippet}>“{shownSnippet}”</span>
                  {isLongSnippet && (
                    <button className="provenance-expand" onClick={() => setExpanded((open) => !open)}>
                      {expanded ? 'Show less' : 'Show more'}
                    </button>
                  )}
                </dd>
              </div>
            )}
            {reason && <div><dt>Why</dt><dd>{reason}</dd></div>}
          </dl>
        </div>
      )}
    </>
  );
}
