// Canvas ink geometry shared by the web tools. Shapes become ordinary strokes (flat
// [x, y, pressure, ...]), exactly as the Android client draws them (ink/InkCanvasView.kt).

import { Stroke } from './model';

export type ShapeKind = 'line' | 'arrow' | 'rect' | 'ellipse';

/** Outline from (x0, y0) to (x1, y1); straight runs are resampled every ~6 units. */
export function shapePoints(kind: ShapeKind, x0: number, y0: number, x1: number, y1: number): number[] {
  const pts: number[] = [];
  const run = (ax: number, ay: number, bx: number, by: number) => {
    const n = Math.max(1, Math.floor(Math.hypot(bx - ax, by - ay) / 6));
    for (let i = pts.length ? 1 : 0; i <= n; i++) {
      const t = i / n;
      pts.push(ax + (bx - ax) * t, ay + (by - ay) * t);
    }
  };
  if (kind === 'line') run(x0, y0, x1, y1);
  else if (kind === 'arrow') {
    run(x0, y0, x1, y1);
    const len = Math.max(1, Math.hypot(x1 - x0, y1 - y0));
    const head = Math.min(28, Math.max(8, len * 0.25));
    const ux = (x1 - x0) / len;
    const uy = (y1 - y0) / len;
    // Two barbs at ±28° back along the shaft, drawn out and back from the tip.
    const c = 0.883;
    const sn = 0.469;
    const ax = x1 - head * (ux * c - uy * sn);
    const ay = y1 - head * (uy * c + ux * sn);
    const bx = x1 - head * (ux * c + uy * sn);
    const by = y1 - head * (uy * c - ux * sn);
    run(x1, y1, ax, ay);
    run(ax, ay, x1, y1);
    run(x1, y1, bx, by);
  } else if (kind === 'rect') {
    run(x0, y0, x1, y0);
    run(x1, y0, x1, y1);
    run(x1, y1, x0, y1);
    run(x0, y1, x0, y0);
  } else {
    const cx = (x0 + x1) / 2;
    const cy = (y0 + y1) / 2;
    const rx = Math.abs(x1 - x0) / 2;
    const ry = Math.abs(y1 - y0) / 2;
    for (let i = 0; i <= 72; i++) {
      const a = (i * 2 * Math.PI) / 72;
      pts.push(cx + rx * Math.cos(a), cy + ry * Math.sin(a));
    }
  }
  const out: number[] = [];
  for (let i = 0; i < pts.length; i += 2) out.push(pts[i], pts[i + 1], 0.5);
  return out;
}

const segDist = (px: number, py: number, ax: number, ay: number, bx: number, by: number) => {
  const dx = bx - ax;
  const dy = by - ay;
  const l2 = dx * dx + dy * dy;
  const t = l2 ? Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / l2)) : 0;
  return Math.hypot(px - (ax + t * dx), py - (ay + t * dy));
};

/** Whether the eraser at (x, y) with radius `r` touches the stroke's line. */
export function strokeHit(s: Stroke, x: number, y: number, r: number): boolean {
  const p = s.points;
  const reach = r + s.width / 2;
  if (p.length < 6) return p.length >= 2 && Math.hypot(p[0] - x, p[1] - y) <= reach;
  for (let i = 3; i < p.length; i += 3) if (segDist(x, y, p[i - 3], p[i - 2], p[i], p[i + 1]) <= reach) return true;
  return false;
}

/** SVG path through a stroke's points. */
export function strokePath(p: number[]): string {
  let d = '';
  for (let i = 0; i < p.length; i += 3) d += `${i ? ' L' : 'M'} ${p[i]} ${p[i + 1]}`;
  return d;
}
