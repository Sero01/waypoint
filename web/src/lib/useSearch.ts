import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError, search, type SearchParams, type TimedSearch } from './api';

/**
 * The state of one search, driven by whatever is currently in the URL.
 *
 * Requests are aborted when they are superseded. Waypoint answers in single
 * digit milliseconds, so overlapping requests are rare — but the operator
 * toggle re-runs on click, and without this a slow first request could land
 * after a fast second one and put the wrong results on screen.
 */
export interface SearchState {
  readonly result: TimedSearch | null;
  readonly error: ApiError | null;
  readonly loading: boolean;
  /** The parameters `result` belongs to, so the UI never labels stale results with new controls. */
  readonly of: SearchParams | null;
}

const IDLE: SearchState = { result: null, error: null, loading: false, of: null };

export function useSearch(params: SearchParams): SearchState & { retry: () => void } {
  const [state, setState] = useState<SearchState>(IDLE);
  const [attempt, setAttempt] = useState(0);
  const inFlight = useRef<AbortController | null>(null);

  const retry = useCallback(() => setAttempt((n) => n + 1), []);

  const { q, k, op } = params;

  useEffect(() => {
    inFlight.current?.abort();

    if (!q.trim()) {
      inFlight.current = null;
      setState(IDLE);
      return;
    }

    const controller = new AbortController();
    inFlight.current = controller;
    const current: SearchParams = { q, k, op };

    setState((previous) => ({ ...previous, loading: true, error: null }));

    search(current, controller.signal)
      .then((result) => {
        if (controller.signal.aborted) return;
        setState({ result, error: null, loading: false, of: current });
      })
      .catch((cause: unknown) => {
        if (controller.signal.aborted) return;
        // An abort that raced past the check above is not a failure to report.
        if (cause instanceof DOMException && cause.name === 'AbortError') return;
        const error =
          cause instanceof ApiError
            ? cause
            : new ApiError('server', cause instanceof Error ? cause.message : String(cause));
        setState({ result: null, error, loading: false, of: current });
      });

    return () => controller.abort();
  }, [q, k, op, attempt]);

  return { ...state, retry };
}
