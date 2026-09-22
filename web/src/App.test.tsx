import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import App from './App';

const STATS = {
  documents: 1000000,
  terms: 416287,
  tokens: 57513825,
  averageDocumentLength: 57.513824,
  fileBytes: 85102422,
};

function hitsResponse(overrides: Record<string, unknown> = {}) {
  return {
    query: 'manhattan',
    operator: 'or',
    totalHits: 555,
    totalHitsExact: true,
    returned: 2,
    hits: [
      { id: '685791', score: 6.941892 },
      { id: '349383', score: 6.0071783 },
    ],
    ...overrides,
  };
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

/**
 * A stand-in service. Handlers are matched against the request URL so a test
 * can override just the endpoint it cares about; everything else behaves like
 * a healthy 1M-document index without positions, which is what the repository
 * actually ships.
 */
function stubService(handlers: Array<[RegExp, () => Response]> = []) {
  const calls: string[] = [];
  const fetchMock = vi.fn((input: RequestInfo | URL) => {
    const url = String(input);
    calls.push(url);
    for (const [pattern, respond] of handlers) {
      if (pattern.test(url)) return Promise.resolve(respond());
    }
    if (url.startsWith('/stats')) return Promise.resolve(json(STATS));
    if (url.includes('op=phrase')) {
      return Promise.resolve(
        json(
          { error: 'this index was built without positions, so phrase search is unavailable' },
          503,
        ),
      );
    }
    if (url.startsWith('/search')) return Promise.resolve(json(hitsResponse()));
    return Promise.resolve(json({ error: 'ok' }));
  });
  vi.stubGlobal('fetch', fetchMock);
  return { calls, fetchMock };
}

function visit(search: string) {
  window.history.replaceState(null, '', `/${search}`);
}

beforeEach(() => {
  visit('');
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('first load', () => {
  it('greets with the benchmark queries and runs no search', async () => {
    const { calls } = stubService();
    render(<App />);

    expect(await screen.findByRole('button', { name: 'manhattan' })).toBeInTheDocument();
    expect(calls.some((url) => url.startsWith('/search?q=manhattan&'))).toBe(false);
  });

  it('shows the index it is pointed at', async () => {
    stubService();
    render(<App />);

    const stats = await screen.findByLabelText('Index statistics');
    expect(within(stats).getByText('1,000,000')).toBeInTheDocument();
    expect(within(stats).getByText('81 MB')).toBeInTheDocument();
  });

  it('reports an unreachable service instead of an empty page', async () => {
    stubService([[/\/stats/, () => json({ error: 'index not built yet: /srv/x.wpt' }, 503)]]);
    render(<App />);

    expect(await screen.findByTitle('index not built yet: /srv/x.wpt')).toBeInTheDocument();
    expect(screen.getByText('unavailable')).toBeInTheDocument();
  });
});

describe('searching', () => {
  it('runs the query in the URL on load', async () => {
    visit('?q=manhattan');
    stubService();
    render(<App />);

    expect(await screen.findByText('685791')).toBeInTheDocument();
    expect(screen.getByText('6.9419')).toBeInTheDocument();
    expect(screen.getByText('349383')).toBeInTheDocument();
  });

  it('puts a submitted query in the URL so it can be shared', async () => {
    stubService();
    const user = userEvent.setup();
    render(<App />);

    await user.type(await screen.findByLabelText('Search query'), 'manhattan');
    await user.click(screen.getByRole('button', { name: 'Search' }));

    await waitFor(() => expect(window.location.search).toBe('?q=manhattan'));
    expect(await screen.findByText('685791')).toBeInTheDocument();
  });

  it('reports the count as a lower bound when pruning stopped the walk short', async () => {
    visit('?q=manhattan');
    stubService([
      [/\/search/, () => json(hitsResponse({ totalHits: 4812, totalHitsExact: false }))],
    ]);
    render(<App />);

    expect(await screen.findByText('lower bound')).toBeInTheDocument();
    expect(screen.getByText(/matching documents or more/)).toBeInTheDocument();
  });

  it('does not claim a lower bound when the count is exact', async () => {
    visit('?q=manhattan');
    stubService();
    render(<App />);

    await screen.findByText('685791');
    expect(screen.queryByText('lower bound')).not.toBeInTheDocument();
  });

  it('re-runs against the service when the operator changes', async () => {
    visit('?q=blood+pressure');
    const { calls } = stubService();
    const user = userEvent.setup();
    render(<App />);

    await screen.findByText('685791');
    await user.click(screen.getByRole('button', { name: 'and' }));

    await waitFor(() =>
      expect(calls.some((url) => url.includes('q=blood+pressure') && url.includes('op=and'))).toBe(
        true,
      ),
    );
    expect(window.location.search).toContain('op=and');
  });

  it('explains an empty result set in the terms of the operator used', async () => {
    visit('?q=nothing&op=and');
    stubService([
      [
        /\/search\?q=nothing/,
        () =>
          json(
            hitsResponse({ operator: 'and', totalHits: 0, totalHitsExact: true, returned: 0, hits: [] }),
          ),
      ],
    ]);
    render(<App />);

    expect(await screen.findByText('Nothing matched')).toBeInTheDocument();
    expect(screen.getByText(/every one of those terms/)).toBeInTheDocument();
  });
});

describe('when the index cannot serve the request', () => {
  it('disables phrase search on an index built without positions', async () => {
    stubService();
    render(<App />);

    await waitFor(() => expect(screen.getByRole('button', { name: 'phrase' })).toBeDisabled());
    expect(screen.getByRole('button', { name: 'phrase' })).toHaveAttribute(
      'title',
      expect.stringContaining('without positions'),
    );
  });

  it('leaves phrase search enabled when the index has positions', async () => {
    stubService([
      [
        /op=phrase/,
        () =>
          json(
            hitsResponse({ operator: 'phrase', totalHits: 0, totalHitsExact: true, returned: 0, hits: [] }),
          ),
      ],
    ]);
    render(<App />);

    await screen.findByLabelText('Index statistics');
    await waitFor(() => expect(screen.getByRole('button', { name: 'phrase' })).toBeEnabled());
  });

  it('shows a rejected query in the service-s own words, with no retry', async () => {
    visit('?q=%21%21%21');
    stubService([
      [/\/search/, () => json({ error: 'query contains no indexable terms' }, 400)],
    ]);
    render(<App />);

    const alert = await screen.findByRole('alert');
    expect(within(alert).getByText('query contains no indexable terms')).toBeInTheDocument();
    expect(within(alert).queryByRole('button', { name: 'Try again' })).not.toBeInTheDocument();
  });

  it('offers a retry for a failure the user cannot fix by editing the query', async () => {
    visit('?q=manhattan');
    let attempts = 0;
    stubService([
      [
        /\/search\?q=manhattan/,
        () => {
          attempts += 1;
          return attempts === 1
            ? json({ error: 'index not built yet: /srv/x.wpt' }, 503)
            : json(hitsResponse());
        },
      ],
    ]);
    const user = userEvent.setup();
    render(<App />);

    await user.click(await screen.findByRole('button', { name: 'Try again' }));

    expect(await screen.findByText('685791')).toBeInTheDocument();
  });
});
