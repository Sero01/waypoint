/**
 * The client for the Waypoint HTTP surface.
 *
 * The service answers `GET /search`, `GET /stats` and `GET /health`, and it
 * maps its errors deliberately: 400 is the caller's problem, 503 is the
 * operator's and is retryable. Both carry `{"error": "..."}` with a sentence
 * written for a human. This module preserves that distinction instead of
 * flattening everything into "request failed", because the two demand
 * different things of whoever is looking at the screen.
 */

export type Operator = 'or' | 'and' | 'phrase';

export const OPERATORS: readonly Operator[] = ['or', 'and', 'phrase'];

export function isOperator(value: unknown): value is Operator {
  return typeof value === 'string' && (OPERATORS as readonly string[]).includes(value);
}

export interface Hit {
  readonly id: string;
  readonly score: number;
}

export interface SearchResponse {
  readonly query: string;
  readonly operator: Operator;
  readonly totalHits: number;
  /**
   * False when MaxScore pruning stopped a disjunction short of enumerating
   * every match, exactly as Lucene's TotalHits.Relation reports it. The count
   * is then a lower bound, not a total.
   */
  readonly totalHitsExact: boolean;
  readonly returned: number;
  readonly hits: readonly Hit[];
}

export interface Stats {
  readonly documents: number;
  readonly terms: number;
  readonly tokens: number;
  readonly averageDocumentLength: number;
  readonly fileBytes: number;
}

/** A search that came back, plus the round trip it took to get here. */
export interface TimedSearch {
  readonly response: SearchResponse;
  /** Wall clock in the browser, request to parsed body, in milliseconds. */
  readonly elapsedMs: number;
}

/**
 * Why a request failed, in the terms the service itself uses.
 *
 * - `query`   the query cannot be served as written (HTTP 400)
 * - `index`   the index is missing or lacks a feature (HTTP 503)
 * - `network` the service was not reached at all
 * - `server`  anything else it returned
 */
export type FailureKind = 'query' | 'index' | 'network' | 'server';

export class ApiError extends Error {
  readonly kind: FailureKind;
  readonly status: number | null;

  constructor(kind: FailureKind, message: string, status: number | null = null) {
    super(message);
    this.name = 'ApiError';
    this.kind = kind;
    this.status = status;
  }

  /** True when retrying later could plausibly succeed without the user changing anything. */
  get retryable(): boolean {
    return this.kind === 'network' || this.kind === 'index';
  }
}

function kindForStatus(status: number): FailureKind {
  if (status === 400) return 'query';
  if (status === 503) return 'index';
  return 'server';
}

async function readError(response: Response): Promise<ApiError> {
  const kind = kindForStatus(response.status);
  let message = `${response.status} ${response.statusText}`.trim();
  try {
    const body: unknown = await response.json();
    if (
      body !== null &&
      typeof body === 'object' &&
      'error' in body &&
      typeof (body as { error: unknown }).error === 'string'
    ) {
      message = (body as { error: string }).error;
    }
  } catch {
    // A non-JSON error body is still an error; the status line above stands in.
  }
  return new ApiError(kind, message, response.status);
}

async function getJson<T>(path: string, signal?: AbortSignal): Promise<T> {
  let response: Response;
  try {
    response = await fetch(path, {
      signal: signal ?? null,
      headers: { Accept: 'application/json' },
    });
  } catch (cause) {
    if (cause instanceof DOMException && cause.name === 'AbortError') throw cause;
    throw new ApiError('network', 'could not reach the search service');
  }
  if (!response.ok) throw await readError(response);
  return (await response.json()) as T;
}

export interface SearchParams {
  readonly q: string;
  readonly k: number;
  readonly op: Operator;
}

export function searchUrl({ q, k, op }: SearchParams): string {
  const params = new URLSearchParams({ q, k: String(k), op });
  return `/search?${params.toString()}`;
}

export async function search(
  params: SearchParams,
  signal?: AbortSignal,
): Promise<TimedSearch> {
  const started = performance.now();
  const response = await getJson<SearchResponse>(searchUrl(params), signal);
  // Read the clock before rendering so the number is the service's round trip
  // and not a measure of how fast React is today.
  const elapsedMs = performance.now() - started;
  return { response, elapsedMs };
}

export async function fetchStats(signal?: AbortSignal): Promise<Stats> {
  return getJson<Stats>('/stats', signal);
}

/**
 * Whether this index can answer phrase queries.
 *
 * There is no endpoint that says so, and `hasPositions` is not in `/stats`, so
 * the only honest way to find out is to ask for a phrase and read the answer.
 * Two tokens that will not be in any corpus keep the probe cheap: the engine
 * looks them up, finds nothing, and returns an empty result set. A 503 naming
 * positions is a definite no; anything else is treated as yes, since the cost
 * of guessing wrong is one error message the user can read.
 */
export async function probePhraseSupport(signal?: AbortSignal): Promise<boolean> {
  try {
    await search({ q: 'zzqxwv yyzqwx', k: 1, op: 'phrase' }, signal);
    return true;
  } catch (error) {
    if (error instanceof ApiError && error.kind === 'index') {
      return !error.message.includes('positions');
    }
    throw error;
  }
}
