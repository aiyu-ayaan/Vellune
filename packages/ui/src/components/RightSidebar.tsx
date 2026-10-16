import React, { useMemo } from 'react';
import { ArrowDownLeft, ArrowUpRight, Info, Link2, MessageSquare } from 'lucide-react';
import { Page } from '../model';
import { buildIndex, linkMention, unlinkedMentions } from '../links';
import { store, useStore } from '../store';
import { useSync } from '../cloud/sync';

export const RightSidebar: React.FC<{ page: Page }> = ({ page }) => {
  const { pages } = useStore((s) => s);
  const index = buildIndex(Object.values(pages));

  const backlinks = index.backlinks.get(page.id) ?? [];
  const outgoingLinks = Array.from(index.outgoing.get(page.id)?.resolved ?? []);
  const roles = useSync((s) => s.roles);
  const openThreads = (page.comments ?? []).filter((c) => !c.parentId && !c.resolved);
  const unlinked = useMemo(() => unlinkedMentions(Object.values(pages), page), [pages, page]);
  const link = (m: (typeof unlinked)[number]) =>
    store.updatePage(m.sourceId, (p) => ({
      ...p,
      blocks: p.blocks.map((b) => (b.id === m.blockId ? { ...b, text: linkMention(b.text, m.start, m.length, page.title) } : b)),
    }));

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

      {/* Open comment threads on this page */}
      {openThreads.length > 0 && (
        <div className="panel-section">
          <div className="panel-header">
            <MessageSquare size={14} />
            <span>Comments ({openThreads.length})</span>
          </div>
          {openThreads.map((c) => {
            const replies = (page.comments ?? []).filter((r) => r.parentId === c.id).length;
            const block = page.blocks.find((b) => b.id === c.blockId);
            return (
              <div
                key={c.id}
                className="backlink-card"
                onClick={() => {
                  const el = document.getElementById(`b-${c.blockId}`);
                  el?.scrollIntoView({ block: 'center' });
                  el?.classList.add('flash');
                  setTimeout(() => el?.classList.remove('flash'), 1600);
                }}
              >
                <div className="backlink-title">{c.authorName}</div>
                <div className="backlink-context">{c.text}</div>
                <div className="backlink-context">{block ? `On: ${block.text.slice(0, 60) || block.kind}` : 'On a deleted block'}{replies ? ` · ${replies} repl${replies === 1 ? 'y' : 'ies'}` : ''}</div>
              </div>
            );
          })}
        </div>
      )}

      {/* Unlinked mentions: the title in plain text elsewhere, one click from becoming a link. */}
      {unlinked.length > 0 && (
        <div className="panel-section">
          <div className="panel-header">
            <Link2 size={14} />
            <span>Unlinked mentions ({unlinked.length})</span>
          </div>
          {unlinked.map((m) => {
            const source = pages[m.sourceId];
            if (!source) return null;
            return (
              <div key={`${m.blockId}-${m.start}`} className="backlink-card unlinked" onClick={() => store.open(m.sourceId, 'page')}>
                <div className="backlink-title">{source.title || 'Untitled'}</div>
                <div className="backlink-context" title={m.context}>
                  {m.context}
                </div>
                {roles[m.sourceId] !== 'viewer' && (
                  <button
                    type="button"
                    className="unlinked-link"
                    onClick={(e) => {
                      e.stopPropagation();
                      link(m);
                    }}
                  >
                    Link
                  </button>
                )}
              </div>
            );
          })}
        </div>
      )}

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
