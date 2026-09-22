import { describe, expect, it } from 'vitest';
import { bytes, grouped, millis, score, scoreFraction } from './format';

describe('grouped', () => {
  it('separates thousands', () => {
    expect(grouped(1000000)).toBe('1,000,000');
    expect(grouped(416287)).toBe('416,287');
    expect(grouped(0)).toBe('0');
  });
});

describe('score', () => {
  it('keeps four decimals so adjacent ranks stay distinguishable', () => {
    expect(score(6.941892)).toBe('6.9419');
    expect(score(6.941899)).toBe('6.9419');
    expect(score(0)).toBe('0.0000');
  });
});

describe('millis', () => {
  it('keeps a decimal below 10ms, where this project lives', () => {
    expect(millis(2.44)).toBe('2.4 ms');
    expect(millis(9.99)).toBe('10.0 ms');
  });

  it('rounds to whole milliseconds in the tens and hundreds', () => {
    expect(millis(116.8)).toBe('117 ms');
    expect(millis(10)).toBe('10 ms');
  });

  it('switches to seconds past a second', () => {
    expect(millis(1500)).toBe('1.50 s');
  });
});

describe('bytes', () => {
  it('reports plain bytes below a kilobyte', () => {
    expect(bytes(512)).toBe('512 B');
  });

  it('scales up and keeps a decimal only while the number is small', () => {
    expect(bytes(85102422)).toBe('81 MB');
    expect(bytes(1536)).toBe('1.5 KB');
  });
});

describe('scoreFraction', () => {
  it('measures each score against the best on screen', () => {
    expect(scoreFraction(5, 10)).toBe(0.5);
    expect(scoreFraction(10, 10)).toBe(1);
  });

  it('keeps the weakest hit visible instead of collapsing it', () => {
    expect(scoreFraction(0.0001, 10)).toBe(0.02);
  });

  it('does not divide by a zero or absent best score', () => {
    expect(scoreFraction(5, 0)).toBe(0);
    expect(scoreFraction(Number.NaN, 10)).toBe(0);
  });
});
