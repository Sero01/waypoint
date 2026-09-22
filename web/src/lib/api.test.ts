import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError, probePhraseSupport, search, searchUrl } from './api';

function jsonResponse(body: unknown, init: ResponseInit = {}): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
    ...init,
  });
}

function stubFetch(impl: (url: string) => Promise<Response>) {
  vi.stubGlobal(
    'fetch',
    vi.fn((input: RequestInfo | URL) => impl(String(input))),
  );
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('searchUrl', () => {
  it('encodes every parameter the service expects', () => {
    expect(searchUrl({ q: 'blood pressure', k: 25, op: 'and' })).toBe(
      '/search?q=blood+pressure&k=25&op=and',
    );
  });
});

describe('search', () => {
  it('returns the parsed response and a measured round trip', async () => {
    const body = {
      query: 'manhattan',
      operator: 'or',
      totalHits: 555,
      totalHitsExact: true,
      returned: 1,
      hits: [{ id: '685791', score: 6.941892 }],
    };
    stubFetch(async () => jsonResponse(body));

    const { response, elapsedMs } = await search({ q: 'manhattan', k: 1, op: 'or' });

    expect(response).toEqual(body);
    expect(elapsedMs).toBeGreaterThanOrEqual(0);
  });

  it('reports a rejected query as the caller-s problem, in the service-s words', async () => {
    stubFetch(async () =>
      jsonResponse({ error: 'query contains no indexable terms' }, { status: 400 }),
    );

    const error = await search({ q: '!!!', k: 10, op: 'or' }).catch((e: unknown) => e);

    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({
      kind: 'query',
      status: 400,
      message: 'query contains no indexable terms',
      retryable: false,
    });
  });

  it('reports a missing index as retryable', async () => {
    stubFetch(async () =>
      jsonResponse({ error: 'index not built yet: /srv/msmarco.wpt' }, { status: 503 }),
    );

    const error = (await search({ q: 'x', k: 10, op: 'or' }).catch((e: unknown) => e)) as ApiError;

    expect(error.kind).toBe('index');
    expect(error.retryable).toBe(true);
    expect(error.message).toContain('/srv/msmarco.wpt');
  });

  it('falls back to the status line when the error body is not JSON', async () => {
    stubFetch(async () => new Response('<html>gateway</html>', { status: 502, statusText: 'Bad Gateway' }));

    const error = (await search({ q: 'x', k: 10, op: 'or' }).catch((e: unknown) => e)) as ApiError;

    expect(error.kind).toBe('server');
    expect(error.message).toBe('502 Bad Gateway');
  });

  it('distinguishes an unreachable service from one that answered', async () => {
    stubFetch(async () => {
      throw new TypeError('Failed to fetch');
    });

    const error = (await search({ q: 'x', k: 10, op: 'or' }).catch((e: unknown) => e)) as ApiError;

    expect(error.kind).toBe('network');
    expect(error.status).toBeNull();
    expect(error.retryable).toBe(true);
  });

  it('lets an abort propagate rather than dressing it up as a network failure', async () => {
    stubFetch(async () => {
      throw new DOMException('aborted', 'AbortError');
    });

    const error = await search({ q: 'x', k: 10, op: 'or' }).catch((e: unknown) => e);

    expect(error).toBeInstanceOf(DOMException);
    expect(error).not.toBeInstanceOf(ApiError);
  });
});

describe('probePhraseSupport', () => {
  it('is false when the index was built without positions', async () => {
    stubFetch(async () =>
      jsonResponse(
        { error: 'this index was built without positions, so phrase search is unavailable' },
        { status: 503 },
      ),
    );

    await expect(probePhraseSupport()).resolves.toBe(false);
  });

  it('is true when the probe is merely answered with no hits', async () => {
    stubFetch(async () =>
      jsonResponse({
        query: 'zzqxwv yyzqwx',
        operator: 'phrase',
        totalHits: 0,
        totalHitsExact: true,
        returned: 0,
        hits: [],
      }),
    );

    await expect(probePhraseSupport()).resolves.toBe(true);
  });

  it('is true when the index is missing for some other reason, so the user sees that error instead', async () => {
    stubFetch(async () => jsonResponse({ error: 'index not built yet: /srv/x.wpt' }, { status: 503 }));

    await expect(probePhraseSupport()).resolves.toBe(true);
  });
});
