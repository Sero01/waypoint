import { useCallback, useEffect, useState } from 'react';
import {
  fetchStats,
  probePhraseSupport,
  type Operator,
  type SearchParams,
  type Stats,
} from './lib/api';
import { useSearch } from './lib/useSearch';
import {
  DEFAULT_K,
  paramsFromSearch,
  sameSearch,
  searchToParams,
} from './lib/urlState';
import { SearchForm } from './components/SearchForm';
import { Results, ResultsSkeleton } from './components/Results';
import { ErrorPanel, Welcome } from './components/Panels';
import { HealthPill, IndexStats, type HealthState } from './components/IndexStats';

/**
 * What the service could tell us about itself at startup: whether the index is
 * open, how big it is, and whether it can answer phrase queries.
 */
interface ServiceInfo {
  readonly health: HealthState;
  readonly stats: Stats | null;
  readonly phraseAvailable: boolean;
  readonly detail: string | undefined;
}

const UNKNOWN: ServiceInfo = {
  health: 'checking',
  stats: null,
  // Assumed until the probe says otherwise, so the control is never wrongly
  // disabled during the moment before the answer arrives.
  phraseAvailable: true,
  detail: undefined,
};

function useUrlSearchParams(): [SearchParams, (next: SearchParams, replace?: boolean) => void] {
  const [params, setParams] = useState<SearchParams>(() =>
    paramsFromSearch(window.location.search),
  );

  useEffect(() => {
    const onPopState = () => setParams(paramsFromSearch(window.location.search));
    window.addEventListener('popstate', onPopState);
    return () => window.removeEventListener('popstate', onPopState);
  }, []);

  const navigate = useCallback((next: SearchParams, replace = false) => {
    setParams((current) => {
      if (sameSearch(current, next)) return current;
      const url = `${window.location.pathname}${searchToParams(next)}`;
      // Replacing rather than pushing for incidental changes keeps the back
      // button walking through searches instead of through control fiddling.
      if (replace) window.history.replaceState(null, '', url);
      else window.history.pushState(null, '', url);
      return next;
    });
  }, []);

  return [params, navigate];
}

export default function App() {
  const [params, navigate] = useUrlSearchParams();
  const [service, setService] = useState<ServiceInfo>(UNKNOWN);
  const { result, error, loading, of, retry } = useSearch(params);

  useEffect(() => {
    const controller = new AbortController();

    void (async () => {
      try {
        const stats = await fetchStats(controller.signal);
        if (controller.signal.aborted) return;
        // Only worth asking about phrases once we know there is an index.
        const phraseAvailable = await probePhraseSupport(controller.signal).catch(() => true);
        if (controller.signal.aborted) return;
        setService({ health: 'up', stats, phraseAvailable, detail: undefined });
      } catch (cause: unknown) {
        if (controller.signal.aborted) return;
        setService({
          health: 'down',
          stats: null,
          phraseAvailable: false,
          detail: cause instanceof Error ? cause.message : undefined,
        });
      }
    })();

    return () => controller.abort();
  }, []);

  useEffect(() => {
    document.title = params.q ? `${params.q} — Waypoint` : 'Waypoint';
  }, [params.q]);

  const submit = useCallback(
    (q: string) => navigate({ ...params, q: q.trim() }),
    [navigate, params],
  );

  const changeOperator = useCallback(
    (op: Operator) => navigate({ ...params, op }),
    [navigate, params],
  );

  // Result-count changes replace rather than push: nobody wants the back
  // button to step through every k they tried on one query.
  const changeK = useCallback(
    (k: number) => navigate({ ...params, k }, true),
    [navigate, params],
  );

  const hasQuery = params.q.trim().length > 0;
  // Only render results once they belong to the search now in the URL.
  const showResults = result !== null && of !== null && sameSearch(of, params);

  return (
    <div className="shell">
      <header className="masthead">
        <h1 className="wordmark">
          <svg width="20" height="20" viewBox="0 0 32 32" fill="none" aria-hidden="true">
            <path d="M16 4l8 24-8-6-8 6z" fill="currentColor" />
          </svg>
          waypoint
        </h1>
        <p className="tagline">
          A static, read-only, single-file inverted index. BM25 ranking matching Lucene to the last
          ulp.
        </p>
        <span className="masthead-spacer" />
        <HealthPill state={service.health} detail={service.detail} />
      </header>

      <SearchForm
        query={params.q}
        operator={params.op}
        k={params.k}
        busy={loading}
        phraseAvailable={service.phraseAvailable}
        onSubmit={submit}
        onOperatorChange={changeOperator}
        onKChange={changeK}
      />

      {!hasQuery && <Welcome onPick={submit} />}

      {hasQuery && error && <ErrorPanel error={error} onRetry={retry} />}

      {hasQuery && !error && showResults && (
        <Results response={result.response} elapsedMs={result.elapsedMs} />
      )}

      {hasQuery && !error && !showResults && loading && <ResultsSkeleton />}

      {service.stats && <IndexStats stats={service.stats} />}

      <footer className="colophon">
        <p>
          Results are document keys and BM25 scores; the index stores no document text. Timings are
          browser round trips, not the engine&apos;s own — see{' '}
          <code>results/</code> in the repository for the measured engine numbers.
        </p>
      </footer>
    </div>
  );
}

export { DEFAULT_K };
