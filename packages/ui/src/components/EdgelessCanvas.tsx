import React, { useRef, useState } from 'react';
import { MousePointer, PenTool, StickyNote, ZoomIn, ZoomOut, RotateCcw } from 'lucide-react';
import { CanvasItem, Page, Stroke, newId } from '../model';
import { store, useStore } from '../store';

type Tool = 'select' | 'sticky' | 'pen' | 'embed';

/** The SVG ink layer spans ±INK_SPAN around the origin; paths are shifted back by it. */
const INK_SPAN = 5000;
const NOTE_ID = 'note';

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

export const EdgelessCanvas: React.FC<{ page: Page }> = ({ page }) => {
  const { pages } = useStore((s) => s);
  const [tool, setTool] = useState<Tool>('select');
  const [zoom, setZoom] = useState(1);
  const [pan, setPan] = useState({ x: 100, y: 100 });
  const [isPanning, setIsPanning] = useState(false);
  const [panStart, setPanStart] = useState({ x: 0, y: 0 });

  // Freehand drawing state
  const [currentStroke, setCurrentStroke] = useState<Stroke | null>(null);

  // Dragging items state
  const [draggedItemId, setDraggedItemId] = useState<string | null>(null);
  const [dragOffset, setDragOffset] = useState({ x: 0, y: 0 });

  const containerRef = useRef<HTMLDivElement>(null);
  const dark = typeof document !== 'undefined' && document.documentElement.getAttribute('data-theme') === 'dark';

  // Without a placed note card the page's document still shows on its canvas, as on Android.
  const hasContent = !!page.title.trim() || page.blocks.some((b) => b.text.trim());
  const items: CanvasItem[] =
    page.canvas.items.some((i) => i.type === 'note') || !hasContent
      ? page.canvas.items
      : [{ id: NOTE_ID, type: 'note', x: 0, y: 0, w: 460, h: 0 }, ...page.canvas.items];

  /** Dragging the implicit note card places it for real first. */
  const startDrag = (item: CanvasItem) => {
    if (item.id === NOTE_ID && !page.canvas.items.some((i) => i.id === NOTE_ID)) {
      store.updatePage(page.id, (p) => ({ ...p, canvas: { ...p.canvas, items: [{ ...item, h: 300 }, ...p.canvas.items] } }));
    }
    setDraggedItemId(item.id);
  };

  const handleWheel = (e: React.WheelEvent) => {
    e.preventDefault();
    if (e.ctrlKey || e.metaKey) {
      // Zoom
      const delta = e.deltaY > 0 ? 0.9 : 1.1;
      setZoom((z) => Math.min(Math.max(z * delta, 0.25), 3));
    } else {
      // Pan
      setPan((p) => ({ x: p.x - e.deltaX, y: p.y - e.deltaY }));
    }
  };

  const handlePointerDown = (e: React.PointerEvent) => {
    const rect = containerRef.current?.getBoundingClientRect();
    if (!rect) return;

    const canvasX = (e.clientX - rect.left - pan.x) / zoom;
    const canvasY = (e.clientY - rect.top - pan.y) / zoom;

    if (tool === 'pen') {
      const stroke: Stroke = {
        id: newId(),
        color: '#6366f1',
        width: 3,
        points: [canvasX, canvasY, e.pressure || 0.5],
      };
      setCurrentStroke(stroke);
    } else if (tool === 'sticky') {
      const newItem: CanvasItem = {
        id: newId(),
        type: 'sticky',
        x: canvasX - 100,
        y: canvasY - 75,
        w: 200,
        h: 150,
        text: 'New thought...',
        color: 'amber',
      };
      store.updatePage(page.id, (p) => ({
        ...p,
        canvas: { ...p.canvas, items: [...p.canvas.items, newItem] },
      }));
      setTool('select');
    } else if (e.target === containerRef.current || (e.target as HTMLElement).tagName === 'svg') {
      // Clicked blank canvas background -> pan
      setIsPanning(true);
      setPanStart({ x: e.clientX - pan.x, y: e.clientY - pan.y });
    }
  };

  const handlePointerMove = (e: React.PointerEvent) => {
    const rect = containerRef.current?.getBoundingClientRect();
    if (!rect) return;

    if (currentStroke && tool === 'pen') {
      const canvasX = (e.clientX - rect.left - pan.x) / zoom;
      const canvasY = (e.clientY - rect.top - pan.y) / zoom;
      setCurrentStroke({
        ...currentStroke,
        points: [...currentStroke.points, canvasX, canvasY, e.pressure || 0.5],
      });
    } else if (isPanning) {
      setPan({ x: e.clientX - panStart.x, y: e.clientY - panStart.y });
    } else if (draggedItemId) {
      const canvasX = (e.clientX - rect.left - pan.x) / zoom;
      const canvasY = (e.clientY - rect.top - pan.y) / zoom;

      store.updatePage(page.id, (p) => ({
        ...p,
        canvas: {
          ...p.canvas,
          items: p.canvas.items.map((item) =>
            item.id === draggedItemId
              ? { ...item, x: canvasX - dragOffset.x, y: canvasY - dragOffset.y }
              : item
          ),
        },
      }));
    }
  };

  const handlePointerUp = () => {
    if (currentStroke) {
      if (currentStroke.points.length > 3) {
        store.updatePage(page.id, (p) => ({
          ...p,
          canvas: { ...p.canvas, strokes: [...p.canvas.strokes, currentStroke] },
        }));
      }
      setCurrentStroke(null);
    }
    setIsPanning(false);
    setDraggedItemId(null);
  };

  // Convert flat stroke points [x, y, p, x, y, p...] to SVG path
  const pointsToSvgPath = (pts: number[]) => {
    let d = '';
    for (let i = 0; i < pts.length; i += 3) {
      const x = pts[i];
      const y = pts[i + 1];
      if (i === 0) d += `M ${x} ${y}`;
      else d += ` L ${x} ${y}`;
    }
    return d;
  };

  return (
    <div
      ref={containerRef}
      className="canvas-wrapper"
      onWheel={handleWheel}
      onPointerDown={handlePointerDown}
      onPointerMove={handlePointerMove}
      onPointerUp={handlePointerUp}
      style={{ cursor: tool === 'pen' ? 'crosshair' : isPanning ? 'grabbing' : 'default' }}
    >
      {/* Canvas Viewport Plane */}
      <div
        className="canvas-plane"
        style={{
          transform: `translate(${pan.x}px, ${pan.y}px) scale(${zoom})`,
        }}
      >
        {/* SVG layer for vector ink strokes */}
        <svg
          style={{
            position: 'absolute',
            top: -INK_SPAN,
            left: -INK_SPAN,
            width: INK_SPAN * 2,
            height: INK_SPAN * 2,
            pointerEvents: 'none',
          }}
        >
          <g transform={`translate(${INK_SPAN} ${INK_SPAN})`}>
            {page.canvas.strokes.map((s) => (
              <path
                key={s.id}
                d={pointsToSvgPath(s.points)}
                fill="none"
                stroke={inkColor(s.color, dark)}
                strokeOpacity={s.tool === 'highlighter' ? 0.4 : 1}
                strokeWidth={s.width}
                strokeLinecap="round"
                strokeLinejoin="round"
              />
            ))}
            {currentStroke && (
              <path
                d={pointsToSvgPath(currentStroke.points)}
                fill="none"
                stroke={inkColor(currentStroke.color, dark)}
                strokeWidth={currentStroke.width}
                strokeLinecap="round"
                strokeLinejoin="round"
              />
            )}
          </g>
        </svg>

        {/* Canvas Items (Document Note, Sticky Notes, Embedded Pages) */}
        {items.map((item) => {
          if (item.type === 'note') {
            return (
              <div
                key={item.id}
                className="canvas-doc-card"
                style={{
                  left: item.x,
                  top: item.y,
                  width: item.w || 460,
                }}
                onPointerDown={(e) => {
                  e.stopPropagation();
                  startDrag(item);
                  const rect = containerRef.current?.getBoundingClientRect();
                  if (!rect) return;
                  const canvasX = (e.clientX - rect.left - pan.x) / zoom;
                  const canvasY = (e.clientY - rect.top - pan.y) / zoom;
                  setDragOffset({ x: canvasX - item.x, y: canvasY - item.y });
                }}
              >
                <div className="canvas-doc-card-title">{page.title || 'Document'}</div>
                <div style={{ fontSize: '0.88rem', color: 'var(--text-secondary)', maxHeight: 240, overflow: 'hidden' }}>
                  {page.blocks.slice(0, 5).map((b) => (
                    <div key={b.id} style={{ marginBottom: 4 }}>
                      {b.text || '...'}
                    </div>
                  ))}
                  {page.blocks.length > 5 && (
                    <div style={{ color: 'var(--text-muted)', fontSize: '0.8rem', marginTop: 8 }}>
                      + {page.blocks.length - 5} more blocks...
                    </div>
                  )}
                </div>
              </div>
            );
          }

          if (item.type === 'sticky') {
            return (
              <div
                key={item.id}
                className={`canvas-sticky ${item.color || 'amber'}`}
                style={{
                  left: item.x,
                  top: item.y,
                  width: item.w,
                  height: item.h,
                }}
                onPointerDown={(e) => {
                  e.stopPropagation();
                  startDrag(item);
                  const rect = containerRef.current?.getBoundingClientRect();
                  if (!rect) return;
                  const canvasX = (e.clientX - rect.left - pan.x) / zoom;
                  const canvasY = (e.clientY - rect.top - pan.y) / zoom;
                  setDragOffset({ x: canvasX - item.x, y: canvasY - item.y });
                }}
              >
                <textarea
                  value={item.text}
                  onChange={(e) => {
                    const nextText = e.target.value;
                    store.updatePage(page.id, (p) => ({
                      ...p,
                      canvas: {
                        ...p.canvas,
                        items: p.canvas.items.map((it) => (it.id === item.id ? { ...it, text: nextText } : it)),
                      },
                    }));
                  }}
                  style={{
                    width: '100%',
                    height: '100%',
                    background: 'transparent',
                    border: 'none',
                    outline: 'none',
                    resize: 'none',
                    fontFamily: 'inherit',
                    fontSize: '0.9rem',
                    color: 'inherit',
                  }}
                />
              </div>
            );
          }

          if (item.type === 'embed') {
            const embedded = item.pageId ? pages[item.pageId] : null;
            return (
              <div
                key={item.id}
                className="canvas-doc-card"
                style={{
                  left: item.x,
                  top: item.y,
                  width: item.w || 260,
                }}
                onPointerDown={(e) => {
                  e.stopPropagation();
                  startDrag(item);
                  const rect = containerRef.current?.getBoundingClientRect();
                  if (!rect) return;
                  const canvasX = (e.clientX - rect.left - pan.x) / zoom;
                  const canvasY = (e.clientY - rect.top - pan.y) / zoom;
                  setDragOffset({ x: canvasX - item.x, y: canvasY - item.y });
                }}
                onDoubleClick={() => {
                  if (item.pageId) store.open(item.pageId, 'page');
                }}
              >
                <div style={{ fontWeight: 600, color: 'var(--accent)', fontSize: '0.95rem', marginBottom: 4 }}>
                  {embedded?.title || 'Embedded Note'}
                </div>
                <div style={{ fontSize: '0.78rem', color: 'var(--text-muted)' }}>
                  Double click to open
                </div>
              </div>
            );
          }

          return null;
        })}
      </div>

      {/* Floating Canvas Toolbar */}
      <div className="canvas-toolbar">
        <button
          className={`canvas-tool-btn ${tool === 'select' ? 'active' : ''}`}
          onClick={() => setTool('select')}
          title="Select & Move Tool" aria-label="Select & Move Tool"
        >
          <MousePointer size={16} />
          <span>Select</span>
        </button>
        <button
          className={`canvas-tool-btn ${tool === 'sticky' ? 'active' : ''}`}
          onClick={() => setTool('sticky')}
          title="Add Sticky Note" aria-label="Add Sticky Note"
        >
          <StickyNote size={16} />
          <span>Sticky</span>
        </button>
        <button
          className={`canvas-tool-btn ${tool === 'pen' ? 'active' : ''}`}
          onClick={() => setTool('pen')}
          title="Freehand Pen Tool (Vector inking)" aria-label="Freehand Pen Tool (Vector inking)"
        >
          <PenTool size={16} />
          <span>Pen</span>
        </button>

        <div style={{ width: 1, height: 20, background: 'var(--border-color)', margin: '0 4px' }} />

        <button className="canvas-tool-btn" onClick={() => setZoom((z) => Math.min(z * 1.2, 3))} title="Zoom In" aria-label="Zoom In">
          <ZoomIn size={16} />
        </button>
        <button className="canvas-tool-btn" onClick={() => setZoom((z) => Math.max(z / 1.2, 0.25))} title="Zoom Out" aria-label="Zoom Out">
          <ZoomOut size={16} />
        </button>
        <button
          className="canvas-tool-btn"
          onClick={() => {
            setZoom(1);
            setPan({ x: 100, y: 100 });
          }}
          title="Reset Viewport" aria-label="Reset Viewport"
        >
          <RotateCcw size={16} />
          <span>{Math.round(zoom * 100)}%</span>
        </button>
      </div>
    </div>
  );
};
