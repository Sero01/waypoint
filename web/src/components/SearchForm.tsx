import { useEffect, useRef, useState } from 'react';
import { OPERATORS, type Operator } from '../lib/api';
import { DEFAULT_K, MAX_K } from '../lib/urlState';

const K_CHOICES = [10, 25, 50, MAX_K];

const OPERATOR_HELP: Record<Operator, string> = {
  or: 'any term matches; scores add up',
  and: 'every term must be present',
  phrase: 'terms adjacent, in order',
};

export interface SearchFormProps {
  query: string;
  operator: Operator;
  k: number;
  busy: boolean;
  /** False when the open index was built without positions, which no query can fix. */
  phraseAvailable: boolean;
  onSubmit: (query: string) => void;
  onOperatorChange: (operator: Operator) => void;
  onKChange: (k: number) => void;
}

export function SearchForm({
  query,
  operator,
  k,
  busy,
  phraseAvailable,
  onSubmit,
  onOperatorChange,
  onKChange,
}: SearchFormProps) {
  const [draft, setDraft] = useState(query);
  const input = useRef<HTMLInputElement>(null);

  // The URL is the source of truth, so a back button or a shared link has to
  // be able to overwrite what is in the box.
  useEffect(() => setDraft(query), [query]);

  useEffect(() => {
    function onKeyDown(event: KeyboardEvent) {
      const target = event.target;
      const typingElsewhere =
        target instanceof HTMLElement &&
        (target.tagName === 'INPUT' || target.tagName === 'SELECT' || target.isContentEditable);
      if (event.key === '/' && !typingElsewhere) {
        event.preventDefault();
        input.current?.focus();
        input.current?.select();
      }
    }
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, []);

  return (
    <form
      className="search"
      role="search"
      onSubmit={(event) => {
        event.preventDefault();
        onSubmit(draft);
      }}
    >
      <div className="search-row">
        <div className="search-field">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" aria-hidden="true">
            <circle cx="11" cy="11" r="7" stroke="currentColor" strokeWidth="2" />
            <path d="m16.5 16.5 4 4" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
          </svg>
          <label className="visually-hidden" htmlFor="q">
            Search query
          </label>
          <input
            id="q"
            ref={input}
            className="search-input"
            type="search"
            name="q"
            value={draft}
            placeholder="manhattan project physics"
            autoComplete="off"
            autoCorrect="off"
            spellCheck={false}
            enterKeyHint="search"
            onChange={(event) => setDraft(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === 'Escape') {
                setDraft('');
                onSubmit('');
              }
            }}
          />
          <kbd className="slash-hint">/</kbd>
        </div>
        <button className="submit" type="submit" disabled={busy || !draft.trim()}>
          {busy ? 'Searching' : 'Search'}
        </button>
      </div>

      <div className="controls">
        <div className="control">
          <span className="control-label" id="op-label">
            Operator
          </span>
          <div className="segmented" role="group" aria-labelledby="op-label">
            {OPERATORS.map((candidate) => {
              const unavailable = candidate === 'phrase' && !phraseAvailable;
              return (
                <button
                  key={candidate}
                  type="button"
                  aria-pressed={operator === candidate}
                  disabled={unavailable}
                  title={
                    unavailable
                      ? 'This index was built without positions, so phrase search is unavailable.'
                      : OPERATOR_HELP[candidate]
                  }
                  onClick={() => onOperatorChange(candidate)}
                >
                  {candidate}
                </button>
              );
            })}
          </div>
        </div>

        <div className="control">
          <label className="control-label" htmlFor="k">
            Results
          </label>
          <select
            id="k"
            className="k-select"
            value={k}
            onChange={(event) => onKChange(Number(event.target.value))}
          >
            {(K_CHOICES.includes(k) ? K_CHOICES : [...K_CHOICES, k].sort((a, b) => a - b)).map(
              (choice) => (
                <option key={choice} value={choice}>
                  {choice}
                </option>
              ),
            )}
          </select>
        </div>

        <span className="control-note">{OPERATOR_HELP[operator]}</span>
      </div>
    </form>
  );
}

export { DEFAULT_K };
