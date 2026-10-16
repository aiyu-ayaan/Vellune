import { describe, expect, it } from 'vitest';
import { compareProps, propNames, textValues } from './props';
import { newPage, propKind } from './model';

describe('page property helpers', () => {
  const pages = [newPage('a', { props: { status: 'Doing', points: 3 } }), newPage('b', { props: { status: 'Done' } }), newPage('c')];

  it('lists names by use and text values per name', () => {
    expect(propNames(pages)).toEqual(['status', 'points']);
    expect(textValues(pages)).toEqual({ status: ['Doing', 'Done'] });
  });

  it('infers kinds from values', () => {
    expect([true, 2, '2026-01-02', 'x'].map(propKind)).toEqual(['checkbox', 'number', 'date', 'text']);
  });

  it('sorts numbers numerically and missing values last', () => {
    expect([10, '', 2, 'b'].sort(compareProps)).toEqual([2, 10, 'b', '']);
    expect(compareProps(undefined, 1)).toBeGreaterThan(0);
    expect(compareProps('item 2', 'item 10')).toBeLessThan(0);
  });
});
