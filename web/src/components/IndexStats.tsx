import type { Stats } from '../lib/api';
import { bytes, grouped } from '../lib/format';

export type HealthState = 'checking' | 'up' | 'down';

export function HealthPill({ state, detail }: { state: HealthState; detail?: string | undefined }) {
  const label = state === 'up' ? 'index open' : state === 'down' ? 'unavailable' : 'connecting';
  return (
    <span className="health" data-state={state} title={detail ?? label}>
      <span className="health-dot" />
      {label}
    </span>
  );
}

export function IndexStats({ stats }: { stats: Stats }) {
  const rows: Array<[string, string]> = [
    ['Documents', grouped(stats.documents)],
    ['Terms', grouped(stats.terms)],
    ['Tokens', grouped(stats.tokens)],
    ['Avg length', stats.averageDocumentLength.toFixed(1)],
    ['Index file', bytes(stats.fileBytes)],
  ];

  return (
    <dl className="stats" aria-label="Index statistics">
      {rows.map(([label, value]) => (
        <div className="stat" key={label}>
          <dt>{label}</dt>
          <dd>{value}</dd>
        </div>
      ))}
    </dl>
  );
}
