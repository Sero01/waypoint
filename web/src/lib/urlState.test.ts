import { describe, expect, it } from 'vitest';
import { DEFAULT_K, clampK, paramsFromSearch, sameSearch, searchToParams } from './urlState';

describe('paramsFromSearch', () => {
  it('reads a full search', () => {
    expect(paramsFromSearch('?q=blood+pressure&k=25&op=and')).toEqual({
      q: 'blood pressure',
      k: 25,
      op: 'and',
    });
  });

  it('defaults everything that is absent', () => {
    expect(paramsFromSearch('')).toEqual({ q: '', k: DEFAULT_K, op: 'or' });
  });

  it('falls back rather than trusting a hand-edited operator', () => {
    expect(paramsFromSearch('?q=x&op=nonsense').op).toBe('or');
  });

  it('clamps a k the service would reject', () => {
    expect(paramsFromSearch('?q=x&k=9999').k).toBe(100);
    expect(paramsFromSearch('?q=x&k=0').k).toBe(1);
    expect(paramsFromSearch('?q=x&k=abc').k).toBe(DEFAULT_K);
  });
});

describe('searchToParams', () => {
  it('omits defaults so a plain search stays readable', () => {
    expect(searchToParams({ q: 'manhattan', k: DEFAULT_K, op: 'or' })).toBe('?q=manhattan');
  });

  it('keeps anything that is not a default', () => {
    expect(searchToParams({ q: 'manhattan', k: 25, op: 'and' })).toBe('?q=manhattan&k=25&op=and');
  });

  it('is empty for an empty search', () => {
    expect(searchToParams({ q: '', k: DEFAULT_K, op: 'or' })).toBe('');
  });

  it('round trips through paramsFromSearch', () => {
    const original = { q: 'manhattan project physics', k: 50, op: 'and' } as const;
    expect(paramsFromSearch(searchToParams(original))).toEqual(original);
  });
});

describe('clampK', () => {
  it('holds k inside the range the service accepts', () => {
    expect(clampK(0)).toBe(1);
    expect(clampK(101)).toBe(100);
    expect(clampK(10.7)).toBe(10);
  });
});

describe('sameSearch', () => {
  it('compares every field', () => {
    const base = { q: 'a', k: 10, op: 'or' } as const;
    expect(sameSearch(base, { ...base })).toBe(true);
    expect(sameSearch(base, { ...base, k: 20 })).toBe(false);
    expect(sameSearch(base, { ...base, op: 'and' })).toBe(false);
  });
});
