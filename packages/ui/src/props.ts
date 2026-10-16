// Page property helpers shared by Info and the database view.

import { Page, PropValue, propKind } from './model';

/** Property names in use across `pages`, most used first. */
export function propNames(pages: Page[]): string[] {
  const n = new Map<string, number>();
  for (const p of pages) for (const k of Object.keys(p.props ?? {})) n.set(k, (n.get(k) ?? 0) + 1);
  return [...n.entries()].sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0])).map(([k]) => k);
}

/** Distinct text values per property name, so a text property can act like a select. */
export function textValues(pages: Page[]): Record<string, string[]> {
  const out: Record<string, Set<string>> = {};
  for (const p of pages)
    for (const [k, v] of Object.entries(p.props ?? {})) if (propKind(v) === 'text' && v) (out[k] ??= new Set()).add(v as string);
  return Object.fromEntries(Object.entries(out).map(([k, s]) => [k, [...s].sort()]));
}

/** Sort order for property values: missing last, numbers numerically, the rest as text. */
export function compareProps(a: PropValue | undefined, b: PropValue | undefined): number {
  if (a === undefined || a === '') return b === undefined || b === '' ? 0 : 1;
  if (b === undefined || b === '') return -1;
  if (typeof a === 'number' && typeof b === 'number') return a - b;
  return String(a).localeCompare(String(b), undefined, { numeric: true });
}
