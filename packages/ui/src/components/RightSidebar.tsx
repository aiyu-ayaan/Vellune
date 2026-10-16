import React from 'react';
import { ArrowDownLeft, ArrowUpRight, Info } from 'lucide-react';
import { Page } from '../model';
import { buildIndex } from '../links';
import { store, useStore } from '../store';

export const RightSidebar: React.FC<{ page: Page }> = ({ page }) => {
  const { pages } = useStore((s) => s);
  const index = buildIndex(Object.values(pages));

  const backlinks = index.backlinks.get(page.id) ?? [];
  const outgoingLinks = Array.from(index.outgoing.get(page.id)?.resolved ?? []);

  // Calculate word count
  const wordCount = page.blocks
    .map((b) => b.text.trim())
    .join(' ')
    .split(/\s+/)
    .filter(Boolean).length;

  return (
    <aside className="inspector-panel">
      {/* Backlinks */}
      <div className="panel-section">
        <div className="panel-header">
          <ArrowDownLeft size={14} />
          <span>Backlinks ({backlinks.length})</span>
        </div>
        {backlinks.length === 0 ? (
          <div style={{ fontSize: '13px', color: 'var(--text-muted)', padding: '0 8px' }}>
            No incoming backlinks yet.
          </div>
        ) : (
          backlinks.map((bl, i) => {
            const source = pages[bl.sourceId];
            if (!source) return null;
            return (
              <div
                key={`${bl.sourceId}-${i}`}
                className="backlink-card"
                onClick={() => store.open(bl.sourceId, 'page')}
              >
                <div className="backlink-title">{source.title || 'Untitled'}</div>
                {bl.context && (
                  <div className="backlink-context" title={bl.context}>
                    {bl.context.replace(/\[\[([^\]|]+)(?:\|([^\]]+))?\]\]/g, (_, a: string, b?: string) => b ?? a)}
                  </div>
                )}
              </div>
            );
          })
        )}
      </div>

      {/* Outgoing Links */}
      <div className="panel-section">
        <div className="panel-header">
          <ArrowUpRight size={14} />
          <span>Outgoing Links ({outgoingLinks.length})</span>
        </div>
        {outgoingLinks.length === 0 ? (
          <div style={{ fontSize: '13px', color: 'var(--text-muted)', padding: '0 8px' }}>
            No outgoing links in this note.
          </div>
        ) : (
          outgoingLinks.map((targetId) => {
            const target = pages[targetId];
            if (!target) return null;
            return (
              <div
                key={targetId}
                className="backlink-card"
                onClick={() => store.open(targetId, 'page')}
              >
                <div className="backlink-title">{target.title || 'Untitled'}</div>
              </div>
            );
          })
        )}
      </div>

      {/* Note Metadata */}
      <div className="panel-section" style={{ marginTop: 'auto', borderTop: '1px solid var(--border-color)', paddingTop: '12px' }}>
        <div className="panel-header">
          <Info size={14} />
          <span>Note Details</span>
        </div>
        <div style={{ display: 'flex', flexDirection: 'column', gap: '4px', fontSize: '13px', padding: '0 8px', color: 'var(--text-secondary)' }}>
          <div>Words: <strong>{wordCount}</strong></div>
          <div>Blocks: <strong>{page.blocks.length}</strong></div>
          <div>Updated: <strong>{new Date(page.updatedAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}</strong></div>
        </div>
      </div>
    </aside>
  );
};
