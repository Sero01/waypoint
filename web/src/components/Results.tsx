import { useEffect, useState } from 'react';
import type { Hit, SearchResponse } from '../lib/api';
import { grouped, millis, score as formatScore, scoreFraction } from '../lib/format';

function CopyButton({ value }: { value: string }) {
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    if (!copied) return;
    const timer = window.setTimeout(() => setCopied(false), 1200);
    return () => window.clearTimeout(timer);
  }, [copied]);

  return (
    <button
      type="button"
      className="copy"
      data-copied={copied}
      aria-label={copied ? `Copied ${value}` : `Copy document id ${value}`}
      onClick={() => {
        // Not available over plain HTTP on a remote host; failing quietly is
        // better than an error for something this incidental.
        void navigator.clipboard?.writeText(value).then(
          () => setCopied(true),
          () => undefined,
        );
      }}
    >
      {copied ? 'copied' : 'copy'}
    </button>
  );
}

function ResultRow({ hit, rank, best }: { hit: Hit; rank: number; best: number }) {
  const fraction = scoreFraction(hit.score, best);
  return (
    <li className="result">
      <span className="result-rank">{rank}</span>
      <span className="result-id">
        <code>{hit.id}</code>
        <CopyButton value={hit.id} />
      </span>
      <span className="result-score">
        <span
          className="score-bar"
          role="img"
          aria-label={`${Math.round(fraction * 100)} percent of the top score`}
        >
          <span style={{ width: `${fraction * 100}%` }} />
        </span>
        {formatScore(hit.score)}
      </span>
    </li>
  );
}

export interface ResultsProps {
  response: SearchResponse;
  elapsedMs: number;
}

export function Results({ response, elapsedMs }: ResultsProps) {
  const { hits, totalHits, totalHitsExact, operator, returned } = response;
  const best = hits[0]?.score ?? 0;

  return (
    <section aria-label="Search results">
      <p className="receipt">
        <span>
          <strong>{operator}</strong>
        </span>
        <span className="receipt-sep">·</span>
        <span>
          <strong>{grouped(totalHits)}</strong>
          {totalHitsExact ? ' matching documents' : ' matching documents or more'}
        </span>
        {!totalHitsExact && (
          <span
            className="badge"
            data-tone="warn"
            title="MaxScore pruning stopped this disjunction before it enumerated every match, exactly as Lucene's TotalHits.Relation reports it. The count is a lower bound."
          >
            lower bound
          </span>
        )}
        <span className="receipt-sep">·</span>
        <span>
          <strong>{returned}</strong> returned
        </span>
        <span className="receipt-timing" title="Round trip measured in the browser">
          {millis(elapsedMs)}
        </span>
      </p>

      {hits.length === 0 ? (
        <div className="panel" data-tone="neutral">
          <h2>Nothing matched</h2>
          <p>
            {operator === 'and'
              ? 'No document contains every one of those terms. The or operator will score documents that contain some of them.'
              : operator === 'phrase'
                ? 'No document contains those terms adjacent and in that order.'
                : 'No document in this index contains any of those terms.'}
          </p>
        </div>
      ) : (
        <ol className="results">
          {hits.map((hit, index) => (
            <ResultRow key={`${hit.id}-${index}`} hit={hit} rank={index + 1} best={best} />
          ))}
        </ol>
      )}
    </section>
  );
}

export function ResultsSkeleton({ rows = 6 }: { rows?: number }) {
  return (
    <div aria-hidden="true" style={{ marginTop: '1.75rem' }}>
      {Array.from({ length: rows }, (_, index) => (
        <div className="skeleton" key={index} />
      ))}
    </div>
  );
}
