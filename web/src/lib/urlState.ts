/**
 * The query lives in the address bar.
 *
 * A search is a thing you send to someone, and the back button should walk
 * back through what you tried. Both come free if the URL is the source of
 * truth rather than a copy of it.
 */

import { isOperator, type Operator, type SearchParams } from './api';

export const DEFAULT_K = 10;
export const MAX_K = 100;

export function clampK(value: number): number {
  if (!Number.isFinite(value)) return DEFAULT_K;
  return Math.min(MAX_K, Math.max(1, Math.trunc(value)));
}

/** Reads the search out of a query string, falling back for anything absent or malformed. */
export function paramsFromSearch(search: string): SearchParams {
  const params = new URLSearchParams(search);
  const op = params.get('op');
  const k = Number(params.get('k'));
  return {
    q: params.get('q') ?? '',
    k: params.has('k') ? clampK(k) : DEFAULT_K,
    op: isOperator(op) ? (op as Operator) : 'or',
  };
}

/**
 * The query string for a search, with defaults left out.
 *
 * Omitting `k=10&op=or` keeps a plain search's URL short enough to read aloud.
 */
export function searchToParams({ q, k, op }: SearchParams): string {
  const params = new URLSearchParams();
  if (q) params.set('q', q);
  if (k !== DEFAULT_K) params.set('k', String(k));
  if (op !== 'or') params.set('op', op);
  const encoded = params.toString();
  return encoded ? `?${encoded}` : '';
}

export function sameSearch(a: SearchParams, b: SearchParams): boolean {
  return a.q === b.q && a.k === b.k && a.op === b.op;
}
