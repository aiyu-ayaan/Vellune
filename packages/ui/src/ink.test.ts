import { describe, expect, it } from 'vitest';
import { shapePoints, strokeHit } from './ink';

const xy = (p: number[], i: number) => [p[i * 3], p[i * 3 + 1]];

describe('canvas ink', () => {
  it('draws a closed rectangle with neutral pressure, like Android', () => {
    const p = shapePoints('rect', 0, 0, 120, 60);
    expect(p.length % 3).toBe(0);
    expect(xy(p, 0)).toEqual(xy(p, p.length / 3 - 1));
    expect(Array.from({ length: p.length / 3 }, (_, i) => xy(p, i))).toContainEqual([120, 60]);
    expect(Array.from({ length: p.length / 3 }, (_, i) => p[i * 3 + 2]).every((v) => v === 0.5)).toBe(true);
  });

  it('keeps an ellipse in its box and ends an arrow on a barb', () => {
    const e = shapePoints('ellipse', 10, 20, 110, 80);
    for (let i = 0; i < e.length; i += 3) {
      expect(e[i]).toBeGreaterThan(9.99);
      expect(e[i]).toBeLessThan(110.01);
    }
    const a = shapePoints('arrow', 0, 0, 100, 0);
    const end = a[a.length - 3];
    expect(end).toBeGreaterThan(70);
    expect(end).toBeLessThan(100);
  });

  it('hits strokes near their line only', () => {
    const s = { id: 's', color: '#000', width: 2, points: [0, 0, 0.5, 100, 0, 0.5] };
    expect(strokeHit(s, 50, 5, 5)).toBe(true);
    expect(strokeHit(s, 50, 20, 5)).toBe(false);
    expect(strokeHit(s, 130, 0, 5)).toBe(false);
  });
});
