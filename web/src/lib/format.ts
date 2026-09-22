/** Number and byte formatting, kept in one place so the UI reads consistently. */

const GROUPED = new Intl.NumberFormat('en-US');

export function grouped(value: number): string {
  return GROUPED.format(value);
}

/**
 * A score, to four decimals.
 *
 * BM25 scores here are single-precision floats that match Lucene's to the last
 * ulp; four decimals is enough to tell adjacent ranks apart without implying
 * more precision than a float carries.
 */
export function score(value: number): string {
  return value.toFixed(4);
}

/**
 * A duration in milliseconds, with the precision the magnitude deserves.
 *
 * Sub-10ms round trips are the interesting case in this project, so they keep
 * a decimal; past 100ms the tenth is noise.
 */
export function millis(value: number): string {
  if (value < 10) return `${value.toFixed(1)} ms`;
  if (value < 1000) return `${Math.round(value)} ms`;
  return `${(value / 1000).toFixed(2)} s`;
}

const UNITS = ['B', 'KB', 'MB', 'GB', 'TB'] as const;

export function bytes(value: number): string {
  if (value < 1024) return `${value} B`;
  let scaled = value;
  let unit = 0;
  while (scaled >= 1024 && unit < UNITS.length - 1) {
    scaled /= 1024;
    unit += 1;
  }
  return `${scaled.toFixed(scaled < 10 ? 1 : 0)} ${UNITS[unit]}`;
}

/**
 * How wide a score bar should be, as a fraction of the best score on screen.
 *
 * BM25 scores have no ceiling, so the only meaningful comparison is against
 * the other results in the same list. The floor of 0.02 keeps the weakest hit
 * visible rather than collapsing it to nothing.
 */
export function scoreFraction(value: number, best: number): number {
  if (!Number.isFinite(value) || !Number.isFinite(best) || best <= 0) return 0;
  return Math.max(0.02, Math.min(1, value / best));
}
