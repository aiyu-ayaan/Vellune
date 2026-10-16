import React, { useEffect, useRef, useState } from 'react';
import {
  ArrowUpRight,
  Circle,
  Eraser,
  Highlighter,
  Minus,
  MousePointer,
  PenTool,
  Redo2,
  RotateCcw,
  Square,
  StickyNote,
  Trash2,
  Undo2,
  ZoomIn,
  ZoomOut,
} from 'lucide-react';
import { CanvasItem, Page, Stroke, newId } from '../model';
import { store, useStore } from '../store';
import { useSync } from '../cloud/sync';
import { ShapeKind, shapePoints, strokeHit, strokePath } from '../ink';

type Tool = 'select' | 'sticky' | 'pen' | 'highlighter' | 'shape' | 'eraser';

/** The SVG ink layer spans ±INK_SPAN around the origin; paths are shifted back by it. */
const INK_SPAN = 5000;
const NOTE_ID = 'note';
const MIN_ZOOM = 0.25;
const MAX_ZOOM = 3;

const PEN_COLORS = ['#1f1f1f', '#2563eb', '#dc2626', '#16a34a', '#ea580c', '#7c3aed'];
const HIGHLIGHT_COLORS = ['#facc15', '#4ade80', '#60a5fa', '#f472b6'];
const WIDTHS = [2, 4, 8];
const SHAPES: { kind: ShapeKind; label: string; icon: typeof Square }[] = [
  { kind: 'line', label: 'Line', icon: Minus },
  { kind: 'arrow', label: 'Arrow', icon: ArrowUpRight },
  { kind: 'rect', label: 'Rectangle', icon: Square },
  { kind: 'ellipse', label: 'Ellipse', icon: Circle },
];
const KEYS: Record<string, Tool> = { v: 'select', s: 'sticky', p: 'pen', h: 'highlighter', r: 'shape', e: 'eraser' };

/**
 * Same rule as the Android client (Brushes.displayColor): neutral dark ink shows light on a dark
 * page, and highlighters are see-through.
 */
function inkColor(hex: string, dark: boolean): string {
  const m = /^#?([0-9a-f]{6})$/i.exec(hex.trim());
  if (!dark || !m) return hex;
  const n = parseInt(m[1], 16);
  const r = (n >> 16) & 255, g = (n >> 8) & 255, b = n & 255;
  const max = Math.max(r, g, b), min = Math.min(r, g, b);
  const sat = max === 0 ? 0 : (max - min) / max;
  return sat < 0.15 && max / 255 < 0.35 ? '#e6e1e9' : hex;
}

const editingText = (t: EventTarget | null) => t instanceof HTMLElement && !!t.closest('textarea, input, [contenteditable="true"]');

export const EdgelessCanvas: React.FC<{ page: Page }> = ({ page }) => {
  const { pages } = useStore((s) => s);
  const readOnly = useSync((s) => s.roles[page.id] === 'viewer');
  const [tool, setTool] = useState<Tool>('select');
  const [shape, setShape] = useState<ShapeKind>('rect');
  const [penColor, setPenColor] = useState(PEN_COLORS[1]);
  const [highlightColor, setHighlightColor] = useState(HIGHLIGHT_COLORS[0]);
  const [width, setWidth] = useState(WIDTHS[1]);
  const [zoom, setZoom] = useState(1);
  const [pan, setPan] = useState({ x: 100, y: 100 });
  const [selected, setSelected] = useState<string | null>(null);
  const [draft, setDraftState] = useState<Stroke | null>(null);
  // Pointer events can outrun renders, so the stroke in progress lives in a ref as well.
  const draftRef = useRef<Stroke | null>(null);
  const setDraft = (d: Stroke | null) => {
    draftRef.current = d;
    setDraftState(d);
  };
  const gesture = useRef<
    | { kind: 'pan'; x: number; y: number }
    | { kind: 'drag'; id: string; dx: number; dy: number }
    | { kind: 'shape'; x0: number; y0: number }
    | { kind: 'ink' }
    | { kind: 'erase' }
    | null
  >(null);
  // Canvas undo is per page and per session: snapshots taken before each change.
  const undo = useRef<Page['canvas'][]>([]);
  const redo = useRef<Page['canvas'][]>([]);
  const containerRef = useRef<HTMLDivElement>(null);
  const dark = typeof document !== 'undefined' && document.documentElement.getAttribute('data-theme') === 'dark';

  useEffect(() => {
    undo.current = [];
    redo.current = [];
    setSelected(null);
  }, [page.id]);
  useEffect(() => {
    if (readOnly) setTool('select');
  }, [readOnly]);

  // Without a placed note card the page's document still shows on its canvas, as on Android.
  const hasContent = !!page.title.trim() || page.blocks.some((b) => b.text.trim());
  const items: CanvasItem[] =
    page.canvas.items.some((i) => i.type === 'note') || !hasContent
      ? page.canvas.items
      : [{ id: NOTE_ID, type: 'note', x: 0, y: 0, w: 460, h: 0 }, ...page.canvas.items];

  const setCanvas = (fn: (c: Page['canvas']) => Page['canvas'], record = true) => {
    if (readOnly) return;
    if (record) {
      undo.current.push(page.canvas);
      if (undo.current.length > 100) undo.current.shift();
      redo.current = [];
    }
    store.updatePage(page.id, (p) => ({ ...p, canvas: fn(p.canvas) }));
  };
  const step = (from: React.MutableRefObject<Page['canvas'][]>, to: React.MutableRefObject<Page['canvas'][]>) => {
    const prev = from.current.pop();
    if (!prev || readOnly) return;
    to.current.push(page.canvas);
    store.updatePage(page.id, (p) => ({ ...p, canvas: prev }));
  };

  const toWorld = (clientX: number, clientY: number) => {
    const rect = containerRef.current!.getBoundingClientRect();
    return { x: (clientX - rect.left - pan.x) / zoom, y: (clientY - rect.top - pan.y) / zoom };
  };

  /** Zoom by `factor` keeping the screen point (sx, sy) fixed. */
  const zoomAt = (factor: number, sx?: number, sy?: number) => {
    const rect = containerRef.current?.getBoundingClientRect();
    const ax = sx ?? (rect ? rect.width / 2 : 0);
    const ay = sy ?? (rect ? rect.height / 2 : 0);
    const next = Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, zoom * factor));
    setPan({ x: ax - ((ax - pan.x) / zoom) * next, y: ay - ((ay - pan.y) / zoom) * next });
    setZoom(next);
  };

  const deleteSelected = () => {
    if (!selected || selected === NOTE_ID) return;
    setCanvas((c) => ({ ...c, items: c.items.filter((i) => i.id !== selected) }));
    setSelected(null);
  };

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (editingText(e.target)) return;
      const mod = e.ctrlKey || e.metaKey;
      if (mod && e.key.toLowerCase() === 'z') {
        e.preventDefault();
        if (e.shiftKey) step(redo, undo);
        else step(undo, redo);
      } else if (mod && e.key.toLowerCase() === 'y') {
        e.preventDefault();
        step(redo, undo);
      } else if ((e.key === 'Delete' || e.key === 'Backspace') && selected) {
        e.preventDefault();
        deleteSelected();
      } else if (e.key === 'Escape') {
        setSelected(null);
        setTool('select');
      } else if (!mod && !e.altKey && KEYS[e.key.toLowerCase()] && !readOnly) {
        setTool(KEYS[e.key.toLowerCase()]);
      }
    };
    addEventListener('keydown', onKey);
    return () => removeEventListener('keydown', onKey);
  });

  const handleWheel = (e: React.WheelEvent) => {
    e.preventDefault();
    const rect = containerRef.current!.getBoundingClientRect();
    // Ctrl+wheel is also what trackpad pinch sends.
    if (e.ctrlKey || e.metaKey) zoomAt(Math.exp(-e.deltaY * 0.0015), e.clientX - rect.left, e.clientY - rect.top);
    else setPan((p) => ({ x: p.x - e.deltaX, y: p.y - e.deltaY }));
  };

  const eraseAt = (x: number, y: number) => {
    const r = 8 / zoom;
    const hit = page.canvas.strokes.filter((s) => strokeHit(s, x, y, r));
    if (hit.length) setCanvas((c) => ({ ...c, strokes: c.strokes.filter((s) => !hit.includes(s)) }), false);
  };

  const handlePointerDown = (e: React.PointerEvent) => {
    if (e.button !== 0 && e.button !== 1) return;
    const { x, y } = toWorld(e.clientX, e.clientY);
    const onBackground = e.target === containerRef.current || (e.target as Element).closest('svg, .canvas-plane') === e.target;
    containerRef.current?.setPointerCapture(e.pointerId);
    // Middle button, or the select tool on empty space, pans.
    if (e.button === 1 || readOnly || (tool === 'select' && onBackground)) {
      setSelected(null);
      gesture.current = { kind: 'pan', x: e.clientX - pan.x, y: e.clientY - pan.y };
      return;
    }
    if (tool === 'pen' || tool === 'highlighter') {
      const hl = tool === 'highlighter';
      gesture.current = { kind: 'ink' };
      setDraft({
        id: newId(),
        color: hl ? highlightColor : penColor,
        width: hl ? width * 4 : width,
        points: [x, y, e.pressure || 0.5],
        ...(hl ? { tool: 'highlighter' } : {}),
      });
    } else if (tool === 'shape') {
      gesture.current = { kind: 'shape', x0: x, y0: y };
      setDraft({ id: newId(), color: penColor, width, points: [x, y, 0.5] });
    } else if (tool === 'eraser') {
      undo.current.push(page.canvas);
      redo.current = [];
      gesture.current = { kind: 'erase' };
      eraseAt(x, y);
    } else if (tool === 'sticky') {
      const item: CanvasItem = { id: newId(), type: 'sticky', x: x - 100, y: y - 75, w: 200, h: 150, text: '', color: 'amber' };
      setCanvas((c) => ({ ...c, items: [...c.items, item] }));
      setSelected(item.id);
      setTool('select');
      gesture.current = null;
      // Focus the new note's text once it renders.
      requestAnimationFrame(() => document.querySelector<HTMLTextAreaElement>(`[data-item="${item.id}"] textarea`)?.focus());
    }
  };

  const handlePointerMove = (e: React.PointerEvent) => {
    const g = gesture.current;
    if (!g) return;
    if (g.kind === 'pan') return setPan({ x: e.clientX - g.x, y: e.clientY - g.y });
    const { x, y } = toWorld(e.clientX, e.clientY);
    const draft = draftRef.current;
    if (g.kind === 'ink' && draft) {
      // Coalesced events keep fast strokes smooth.
      const evs = e.nativeEvent.getCoalescedEvents?.() ?? [e.nativeEvent];
      const pts = evs.flatMap((ev) => {
        const w = toWorld(ev.clientX, ev.clientY);
        return [w.x, w.y, ev.pressure || 0.5];
      });
      setDraft({ ...draft, points: [...draft.points, ...pts] });
    } else if (g.kind === 'shape' && draft) {
      setDraft({ ...draft, points: shapePoints(shape, g.x0, g.y0, x, y) });
    } else if (g.kind === 'erase') {
      eraseAt(x, y);
    } else if (g.kind === 'drag') {
      setCanvas((c) => ({ ...c, items: c.items.map((it) => (it.id === g.id ? { ...it, x: x - g.dx, y: y - g.dy } : it)) }), false);
    }
  };

  const handlePointerUp = () => {
    const g = gesture.current;
    gesture.current = null;
    const draft = draftRef.current;
    if (draft && (g?.kind === 'ink' || g?.kind === 'shape')) {
      if (draft.points.length > 3) setCanvas((c) => ({ ...c, strokes: [...c.strokes, draft] }));
      setDraft(null);
    }
  };

  /** Pointer down on an item: select it and start moving it. */
  const grab = (e: React.PointerEvent, item: CanvasItem) => {
    if (tool !== 'select' || readOnly || e.button !== 0) return;
    e.stopPropagation();
    setSelected(item.id);
    if (editingText(e.target)) return;
    // Dragging the implicit note card places it for real first.
    const placed = item.id !== NOTE_ID || page.canvas.items.some((i) => i.id === NOTE_ID);
    setCanvas((c) => (placed ? c : { ...c, items: [{ ...item, h: 300 }, ...c.items] }));
    const { x, y } = toWorld(e.clientX, e.clientY);
    containerRef.current?.setPointerCapture(e.pointerId);
    gesture.current = { kind: 'drag', id: item.id, dx: x - item.x, dy: y - item.y };
  };

  const toolButton = (t: Tool, label: string, Icon: typeof Square, key: string) => (
    <button
      type="button"
      className={`canvas-tool-btn ${tool === t ? 'active' : ''}`}
      onClick={() => setTool(t)}
      title={`${label} (${key})`}
      aria-label={label}
      aria-pressed={tool === t}
      disabled={readOnly && t !== 'select'}
    >
      <Icon size={16} aria-hidden="true" />
    </button>
  );

  const inkTool = tool === 'pen' || tool === 'highlighter' || tool === 'shape';
  const colors = tool === 'highlighter' ? HIGHLIGHT_COLORS : PEN_COLORS;
  const color = tool === 'highlighter' ? highlightColor : penColor;
  const cursor = tool === 'select' ? (gesture.current?.kind === 'pan' ? 'grabbing' : 'default') : tool === 'eraser' ? 'cell' : 'crosshair';

  return (
    <div
      ref={containerRef}
      className="canvas-wrapper"
      onWheel={handleWheel}
      onPointerDown={handlePointerDown}
      onPointerMove={handlePointerMove}
      onPointerUp={handlePointerUp}
      onPointerCancel={handlePointerUp}
      style={{ cursor, touchAction: 'none' }}
    >
      <div className="canvas-plane" style={{ transform: `translate(${pan.x}px, ${pan.y}px) scale(${zoom})` }}>
        <svg
          style={{ position: 'absolute', top: -INK_SPAN, left: -INK_SPAN, width: INK_SPAN * 2, height: INK_SPAN * 2, pointerEvents: 'none' }}
        >
          <g transform={`translate(${INK_SPAN} ${INK_SPAN})`}>
            {[...page.canvas.strokes, ...(draft ? [draft] : [])].map((s) => (
              <path
                key={s.id}
                d={strokePath(s.points)}
                fill="none"
                stroke={inkColor(s.color, dark)}
                strokeOpacity={s.tool === 'highlighter' ? 0.4 : 1}
                strokeWidth={s.width}
                strokeLinecap="round"
                strokeLinejoin="round"
              />
            ))}
          </g>
        </svg>

        {items.map((item) => {
          const sel = selected === item.id ? ' selected' : '';
          if (item.type === 'note') {
            return (
              <div
                key={item.id}
                data-item={item.id}
                className={`canvas-doc-card${sel}`}
                style={{ left: item.x, top: item.y, width: item.w || 460 }}
                onPointerDown={(e) => grab(e, item)}
                onDoubleClick={() => store.open(page.id, 'page')}
                title="Double-click to edit the document"
              >
                <div className="canvas-doc-card-title">
                  {page.icon && <span aria-hidden="true">{page.icon} </span>}
                  {page.title || 'Document'}
                </div>
                <div style={{ fontSize: '0.88rem', color: 'var(--text-secondary)', maxHeight: 240, overflow: 'hidden' }}>
                  {page.blocks.slice(0, 5).map((b) => (
                    <div key={b.id} style={{ marginBottom: 4, marginLeft: (b.indent ?? 0) * 16 }}>
                      {b.text || '…'}
                    </div>
                  ))}
                  {page.blocks.length > 5 && (
                    <div style={{ color: 'var(--text-muted)', fontSize: '0.8rem', marginTop: 8 }}>+ {page.blocks.length - 5} more blocks…</div>
                  )}
                </div>
              </div>
            );
          }
          if (item.type === 'sticky') {
            return (
              <div
                key={item.id}
                data-item={item.id}
                className={`canvas-sticky ${item.color || 'amber'}${sel}`}
                style={{ left: item.x, top: item.y, width: item.w, height: item.h }}
                onPointerDown={(e) => grab(e, item)}
              >
                <div className="canvas-sticky-grip" aria-hidden="true" />
                <textarea
                  value={item.text}
                  readOnly={readOnly}
                  placeholder="Write something…"
                  aria-label="Sticky note"
                  onFocus={() => setSelected(item.id)}
                  onChange={(e) => {
                    const text = e.target.value;
                    setCanvas((c) => ({ ...c, items: c.items.map((it) => (it.id === item.id ? { ...it, text } : it)) }), false);
                  }}
                  className="canvas-sticky-text"
                />
              </div>
            );
          }
          if (item.type === 'embed') {
            const embedded = item.pageId ? pages[item.pageId] : null;
            return (
              <div
                key={item.id}
                data-item={item.id}
                className={`canvas-doc-card${sel}`}
                style={{ left: item.x, top: item.y, width: item.w || 260 }}
                onPointerDown={(e) => grab(e, item)}
                onDoubleClick={() => item.pageId && store.open(item.pageId, 'page')}
              >
                <div style={{ fontWeight: 600, color: 'var(--accent)', fontSize: '0.95rem', marginBottom: 4 }}>
                  {embedded?.icon && <span aria-hidden="true">{embedded.icon} </span>}
                  {embedded?.title || 'Embedded Note'}
                </div>
                <div style={{ fontSize: '0.78rem', color: 'var(--text-muted)' }}>Double-click to open</div>
              </div>
            );
          }
          return null;
        })}
      </div>

      {inkTool && !readOnly && (
        <div className="canvas-palette" role="toolbar" aria-label="Ink options" onPointerDown={(e) => e.stopPropagation()}>
          {tool === 'shape' &&
            SHAPES.map((s) => (
              <button
                key={s.kind}
                type="button"
                className={`canvas-tool-btn ${shape === s.kind ? 'active' : ''}`}
                title={s.label}
                aria-label={s.label}
                aria-pressed={shape === s.kind}
                onClick={() => setShape(s.kind)}
              >
                <s.icon size={15} aria-hidden="true" />
              </button>
            ))}
          {tool === 'shape' && <span className="canvas-sep" aria-hidden="true" />}
          {colors.map((c) => (
            <button
              key={c}
              type="button"
              className={`canvas-swatch ${color === c ? 'active' : ''}`}
              style={{ background: inkColor(c, dark) }}
              aria-label={`Color ${c}`}
              aria-pressed={color === c}
              onClick={() => (tool === 'highlighter' ? setHighlightColor(c) : setPenColor(c))}
            />
          ))}
          <span className="canvas-sep" aria-hidden="true" />
          {WIDTHS.map((w) => (
            <button
              key={w}
              type="button"
              className={`canvas-tool-btn ${width === w ? 'active' : ''}`}
              aria-label={`Size ${w}`}
              aria-pressed={width === w}
              onClick={() => setWidth(w)}
            >
              <span className="canvas-width-dot" style={{ width: w + 3, height: w + 3 }} />
            </button>
          ))}
        </div>
      )}

      <div className="canvas-toolbar" role="toolbar" aria-label="Canvas tools" onPointerDown={(e) => e.stopPropagation()}>
        {toolButton('select', 'Select and pan', MousePointer, 'V')}
        {toolButton('pen', 'Pen', PenTool, 'P')}
        {toolButton('highlighter', 'Highlighter', Highlighter, 'H')}
        {toolButton('shape', 'Shapes', SHAPES.find((s) => s.kind === shape)!.icon, 'R')}
        {toolButton('eraser', 'Eraser', Eraser, 'E')}
        {toolButton('sticky', 'Sticky note', StickyNote, 'S')}
        <span className="canvas-sep" aria-hidden="true" />
        <button type="button" className="canvas-tool-btn" onClick={() => step(undo, redo)} title="Undo (Ctrl+Z)" aria-label="Undo" disabled={readOnly}>
          <Undo2 size={16} aria-hidden="true" />
        </button>
        <button type="button" className="canvas-tool-btn" onClick={() => step(redo, undo)} title="Redo (Ctrl+Shift+Z)" aria-label="Redo" disabled={readOnly}>
          <Redo2 size={16} aria-hidden="true" />
        </button>
        {selected && selected !== NOTE_ID && !readOnly && (
          <button type="button" className="canvas-tool-btn" onClick={deleteSelected} title="Delete selected (Delete)" aria-label="Delete selected item">
            <Trash2 size={16} aria-hidden="true" />
          </button>
        )}
        <span className="canvas-sep" aria-hidden="true" />
        <button type="button" className="canvas-tool-btn" onClick={() => zoomAt(1 / 1.2)} title="Zoom out" aria-label="Zoom out">
          <ZoomOut size={16} aria-hidden="true" />
        </button>
        <button
          type="button"
          className="canvas-tool-btn"
          onClick={() => {
            setZoom(1);
            setPan({ x: 100, y: 100 });
          }}
          title="Reset view"
          aria-label="Reset view"
        >
          <RotateCcw size={14} aria-hidden="true" />
          <span>{Math.round(zoom * 100)}%</span>
        </button>
        <button type="button" className="canvas-tool-btn" onClick={() => zoomAt(1.2)} title="Zoom in" aria-label="Zoom in">
          <ZoomIn size={16} aria-hidden="true" />
        </button>
      </div>
    </div>
  );
};
