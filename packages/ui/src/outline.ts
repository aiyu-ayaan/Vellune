// Block nesting. Blocks stay a flat list; `indent` makes a block the child of the nearest block
// above it with a smaller indent, so lists nest and toggles fold their children.

import { Block } from './model';

export const MAX_INDENT = 6;

export const depth = (b: Block | undefined): number => b?.indent ?? 0;

/** Indent a block may take at index `i`: at most one deeper than the block above it. */
export const clampIndent = (blocks: Block[], i: number, want: number): number =>
  Math.max(0, Math.min(want, i === 0 ? 0 : depth(blocks[i - 1]) + 1, MAX_INDENT));

/** Index one past the last descendant of block `i`. */
export function subtreeEnd(blocks: Block[], i: number): number {
  const d = depth(blocks[i]);
  let j = i + 1;
  while (j < blocks.length && depth(blocks[j]) > d) j++;
  return j;
}

/** Blocks shown on screen: descendants of a collapsed toggle are hidden. */
export function visibleBlocks(blocks: Block[], collapsed: ReadonlySet<string>): Block[] {
  const out: Block[] = [];
  for (let i = 0; i < blocks.length; i++) {
    out.push(blocks[i]);
    if (blocks[i].kind === 'toggle' && collapsed.has(blocks[i].id)) i = subtreeEnd(blocks, i) - 1;
  }
  return out;
}

/** Shift block `i` and its descendants by `delta` levels, keeping the tree valid. */
export function shiftIndent(blocks: Block[], i: number, delta: number): Block[] {
  const end = subtreeEnd(blocks, i);
  const next = clampIndent(blocks, i, depth(blocks[i]) + delta);
  const by = next - depth(blocks[i]);
  if (!by) return blocks;
  return blocks.map((b, j) => {
    if (j < i || j >= end) return b;
    const d = Math.max(0, Math.min(MAX_INDENT, depth(b) + by));
    return { ...b, indent: d || undefined };
  });
}

/** 1-based position of a numbered block among numbered siblings at its depth. */
export function listNumber(blocks: Block[], i: number): number {
  const d = depth(blocks[i]);
  let n = 1;
  for (let j = i - 1; j >= 0; j--) {
    const dj = depth(blocks[j]);
    if (dj > d) continue; // a child of an earlier item
    if (dj < d || blocks[j].kind !== 'numbered') break;
    n++;
  }
  return n;
}
