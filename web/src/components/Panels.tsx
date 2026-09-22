import type { ApiError } from '../lib/api';

/**
 * The service writes its errors as sentences meant for a person, and
 * distinguishes whose problem each one is. Showing them verbatim is both more
 * accurate and more useful than anything this layer could paraphrase; all the
 * front end adds is a heading that says who can act on it, and a retry button
 * where retrying is meaningful.
 */
export function ErrorPanel({ error, onRetry }: { error: ApiError; onRetry: () => void }) {
  const heading =
    error.kind === 'query'
      ? 'That query cannot be answered as written'
      : error.kind === 'index'
        ? 'The index is not available'
        : error.kind === 'network'
          ? 'The search service did not respond'
          : 'The search service returned an error';

  return (
    <div className="panel" data-tone={error.kind === 'query' ? 'warn' : 'error'} role="alert">
      <h2>{heading}</h2>
      <p>{error.message}</p>
      {error.retryable && (
        <div className="panel-actions">
          <button type="button" className="retry" onClick={onRetry}>
            Try again
          </button>
        </div>
      )}
    </div>
  );
}

export interface ExampleQueriesProps {
  onPick: (query: string) => void;
}

/**
 * The three queries the README benchmarks, so what is on screen can be lined
 * up against the published numbers.
 */
const EXAMPLES = ['manhattan', 'manhattan project physics', 'blood pressure medication'];

export function Welcome({ onPick }: ExampleQueriesProps) {
  return (
    <div className="panel" data-tone="neutral">
      <h2>Search a frozen index</h2>
      <p>
        Waypoint stores document keys and BM25 scores, not document text, so results are ids and
        scores rather than snippets. Ranking matches Lucene&apos;s to the last ulp.
      </p>
      <div className="panel-actions examples">
        <span>Try:</span>
        {EXAMPLES.map((example) => (
          <button key={example} type="button" className="chip" onClick={() => onPick(example)}>
            {example}
          </button>
        ))}
      </div>
    </div>
  );
}
