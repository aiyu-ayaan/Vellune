import React, { useEffect, useState } from 'react';
import { Filter, ZoomIn, ZoomOut, RotateCcw } from 'lucide-react';
import { buildGraph, buildIndex } from '../links';
import { store, useStore } from '../store';

interface NodePos {
  id: string;
  label: string;
  x: number;
  y: number;
  vx: number;
  vy: number;
  isTag?: boolean;
  unresolved?: boolean;
}

export const BrainGraph: React.FC = () => {
  const { pages, activeId } = useStore((s) => s);
  const [mode, setMode] = useState<'global' | 'local'>('global');
  const [selectedTag, setSelectedTag] = useState<string>('all');
  const [hoveredNodeId, setHoveredNodeId] = useState<string | null>(null);

  const [nodes, setNodes] = useState<NodePos[]>([]);
  const [zoom, setZoom] = useState(1);
  const [pan, setPan] = useState({ x: 300, y: 250 });
  const [isDraggingCanvas, setIsDraggingCanvas] = useState(false);
  const [dragStart, setDragStart] = useState({ x: 0, y: 0 });

  const activePages = Object.values(pages).filter((p) => !p.deletedAt);
  const index = buildIndex(activePages);
  const graphData = buildGraph(activePages, index, {
    focusId: mode === 'local' && activeId ? activeId : undefined,
    tag: selectedTag === 'all' ? undefined : selectedTag,
    showTags: true,
    showUnresolved: true,
  });

  const allTags = Array.from(new Set(activePages.flatMap((p) => p.tags))).sort();

  // Initialize node positions with circle/spring layout
  useEffect(() => {
    const count = graphData.nodes.length;
    if (count === 0) {
      setNodes([]);
      return;
    }

    const radius = Math.min(220, count * 25);
    const initialNodes: NodePos[] = graphData.nodes.map((n, i) => {
      const angle = (i / count) * 2 * Math.PI;
      return {
        id: n.id,
        label: n.label,
        x: Math.cos(angle) * radius,
        y: Math.sin(angle) * radius,
        vx: 0,
        vy: 0,
        isTag: n.kind === 'tag',
        unresolved: n.kind === 'unresolved',
      };
    });

    // Run simple relaxation simulation
    for (let step = 0; step < 40; step++) {
      for (let i = 0; i < initialNodes.length; i++) {
        for (let j = i + 1; j < initialNodes.length; j++) {
          const dx = initialNodes[j].x - initialNodes[i].x;
          const dy = initialNodes[j].y - initialNodes[i].y;
          const dist = Math.sqrt(dx * dx + dy * dy) || 1;
          if (dist < 180) {
            const force = ((180 - dist) / dist) * 0.15;
            initialNodes[i].x -= dx * force;
            initialNodes[i].y -= dy * force;
            initialNodes[j].x += dx * force;
            initialNodes[j].y += dy * force;
          }
        }
      }
      // Pull connected nodes together
      for (const edge of graphData.edges) {
        const source = initialNodes.find((n) => n.id === edge.source);
        const target = initialNodes.find((n) => n.id === edge.target);
        if (source && target) {
          const dx = target.x - source.x;
          const dy = target.y - source.y;
          source.x += dx * 0.05;
          source.y += dy * 0.05;
          target.x -= dx * 0.05;
          target.y -= dy * 0.05;
        }
      }
    }

    setNodes(initialNodes);
  }, [graphData.nodes.length, mode, selectedTag, activeId]);

  return (
    <div
      className="graph-container"
      onWheel={(e) => {
        e.preventDefault();
        const delta = e.deltaY > 0 ? 0.9 : 1.1;
        setZoom((z) => Math.min(Math.max(z * delta, 0.3), 3));
      }}
      onPointerDown={(e) => {
        if ((e.target as HTMLElement).tagName === 'svg') {
          setIsDraggingCanvas(true);
          setDragStart({ x: e.clientX - pan.x, y: e.clientY - pan.y });
        }
      }}
      onPointerMove={(e) => {
        if (isDraggingCanvas) {
          setPan({ x: e.clientX - dragStart.x, y: e.clientY - dragStart.y });
        }
      }}
      onPointerUp={() => setIsDraggingCanvas(false)}
    >
      {/* Floating Graph Controls */}
      <div className="graph-controls">
        <div style={{ display: 'flex', gap: 4 }}>
          <button
            className={`view-tab ${mode === 'global' ? 'active' : ''}`}
            onClick={() => setMode('global')}
          >
            Global
          </button>
          <button
            className={`view-tab ${mode === 'local' ? 'active' : ''}`}
            onClick={() => setMode('local')}
            disabled={!activeId}
          >
            Local
          </button>
        </div>

        <div style={{ width: 1, height: 16, background: 'var(--border-color)' }} />

        <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
          <Filter size={14} color="var(--text-muted)" />
          <select
            value={selectedTag}
            onChange={(e) => setSelectedTag(e.target.value)}
            style={{
              background: 'transparent',
              border: 'none',
              color: 'var(--text-primary)',
              fontSize: '0.85rem',
              outline: 'none',
              cursor: 'pointer',
            }}
          >
            <option value="all">All Tags</option>
            {allTags.map((t) => (
              <option key={t} value={t}>
                #{t}
              </option>
            ))}
          </select>
        </div>

        <div style={{ width: 1, height: 16, background: 'var(--border-color)' }} />

        <button className="btn-icon" onClick={() => setZoom((z) => z * 1.2)} title="Zoom In" aria-label="Zoom In">
          <ZoomIn size={14} />
        </button>
        <button className="btn-icon" onClick={() => setZoom((z) => z / 1.2)} title="Zoom Out" aria-label="Zoom Out">
          <ZoomOut size={14} />
        </button>
        <button
          className="btn-icon"
          onClick={() => {
            setZoom(1);
            setPan({ x: 300, y: 250 });
          }}
          title="Reset View" aria-label="Reset View"
        >
          <RotateCcw size={14} />
        </button>
      </div>

      {/* SVG Canvas for Nodes and Edges */}
      <svg
        style={{
          width: '100%',
          height: '100%',
          cursor: isDraggingCanvas ? 'grabbing' : 'grab',
        }}
      >
        <g transform={`translate(${pan.x}, ${pan.y}) scale(${zoom})`}>
          {/* Edges */}
          {graphData.edges.map((edge, i) => {
            const source = nodes.find((n) => n.id === edge.source);
            const target = nodes.find((n) => n.id === edge.target);
            if (!source || !target) return null;

            const isHighlighted =
              hoveredNodeId === edge.source || hoveredNodeId === edge.target;

            return (
              <line
                key={`edge-${i}`}
                x1={source.x}
                y1={source.y}
                x2={target.x}
                y2={target.y}
                stroke={isHighlighted ? 'var(--accent)' : 'var(--border-color)'}
                strokeWidth={isHighlighted ? 2.5 : 1.2}
                opacity={hoveredNodeId && !isHighlighted ? 0.2 : 0.7}
              />
            );
          })}

          {/* Nodes */}
          {nodes.map((node) => {
            const isHovered = hoveredNodeId === node.id;
            const isActive = activeId === node.id;

            return (
              <g
                key={node.id}
                transform={`translate(${node.x}, ${node.y})`}
                onPointerEnter={() => setHoveredNodeId(node.id)}
                onPointerLeave={() => setHoveredNodeId(null)}
                onClick={() => {
                  if (!node.isTag && !node.unresolved) {
                    store.open(node.id, 'page');
                  }
                }}
                style={{ cursor: node.unresolved ? 'default' : 'pointer' }}
              >
                <circle
                  r={isActive ? 12 : node.isTag ? 8 : 10}
                  fill={
                    node.unresolved
                      ? 'var(--text-muted)'
                      : node.isTag
                      ? '#10b981'
                      : isActive
                      ? 'var(--accent)'
                      : 'var(--text-secondary)'
                  }
                  stroke={isHovered ? 'var(--accent-hover)' : 'var(--bg-primary)'}
                  strokeWidth={2}
                />
                <text
                  y={18}
                  textAnchor="middle"
                  fill="var(--text-primary)"
                  fontSize={11}
                  fontWeight={isActive || isHovered ? 600 : 400}
                  style={{ userSelect: 'none' }}
                >
                  {node.label}
                </text>
              </g>
            );
          })}
        </g>
      </svg>
    </div>
  );
};
