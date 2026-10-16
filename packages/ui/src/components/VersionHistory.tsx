import React, { useEffect, useMemo, useRef, useState } from 'react';
import { History, RotateCcw, X } from 'lucide-react';
import { Block, Page } from '../model';
import { store } from '../store';
import { colorFor, sync } from '../cloud/sync';
import { LoggedUpdate, diffBlocks, fetchLog, groupVersions, pageAt } from '../cloud/history';
import { ReadOnlyBlocks } from './PublicPage';
import './cloud.css';

const day = (ms: number) => new Date(ms).toLocaleDateString(undefined, { weekday: 'long', month: 'long', day: 'numeric', year: 'numeric' });
const time = (ms: number) => new Date(ms).toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' });

/** Google Docs-style version history for a synced page: browse sessions, see changes, restore. */
export const VersionHistory: React.FC<{ page: Page; onClose: () => void }> = ({ page, onClose }) => {
  const [log, setLog] = useState<LoggedUpdate[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [selected, setSelected] = useState<number | null>(null);
  const [highlight, setHighlight] = useState(true);
  const [detailed, setDetailed] = useState(false);
  const closeRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    closeRef.current?.focus();
    const key = sync.pageKey(page.id);
    if (!key) {
      setError('Version history is kept on the server. Sign in and let this page sync to see its versions.');
      return;
    }
    fetchLog(page.id, key).then(setLog, () => setError('Could not load version history. Check your connection and try again.'));
  }, [page.id]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose();
    addEventListener('keydown', onKey);
    return () => removeEventListener('keydown', onKey);
  }, [onClose]);

  const versions = useMemo(() => (log ? groupVersions(log, detailed ? -1 : undefined) : []), [log, detailed]);
  const sel = selected ?? versions[0]?.seq ?? null;
  const idx = versions.findIndex((v) => v.seq === sel);
  const preview = useMemo(() => (log && sel !== null ? pageAt(log, sel) : null), [log, sel]);
  const diff = useMemo(() => {
    if (!log || !preview || idx < 0) return null;
    const prevSeq = versions[idx + 1]?.seq;
    const prev = prevSeq === undefined ? null : pageAt(log, prevSeq);
    const d = diffBlocks(prev, preview);
    const kept = new Set(preview.blocks.map((b) => b.id));
    // Show each removed block after the nearest earlier block that survived.
    const removed = d.removed.map((block: Block) => {
      const order = prev?.blocks ?? [];
      let after: string | null = null;
      for (let i = order.findIndex((b) => b.id === block.id) - 1; i >= 0; i--) {
        if (kept.has(order[i].id) || d.removed.some((r) => r.id === order[i].id)) {
          after = order[i].id;
          break;
        }
      }
      return { block, after };
    });
    return { changes: d.changes, removed };
  }, [log, preview, idx, versions]);

  const restore = () => {
    if (!preview) return;
    // A restore is a new edit on top of history, so it can itself be undone from this list.
    store.updatePage(page.id, (cur) => ({
      ...preview,
      id: cur.id,
      favorite: cur.favorite,
      parentId: cur.parentId,
      order: cur.order,
      folder: cur.folder,
      deletedAt: cur.deletedAt,
    }));
    onClose();
  };

  let lastDay = '';
  return (
    <div className="history-overlay" role="dialog" aria-modal="true" aria-label={`Version history of ${page.title || 'Untitled'}`}>
      <header className="history-head">
        <History size={18} aria-hidden="true" />
        <h2>Version history</h2>
        <span className="history-sub">{page.title || 'Untitled'}</span>
        <label className="history-toggle">
          <input type="checkbox" checked={highlight} onChange={(e) => setHighlight(e.target.checked)} /> Show changes
        </label>
        <label className="history-toggle">
          <input type="checkbox" checked={detailed} onChange={(e) => { setDetailed(e.target.checked); setSelected(null); }} /> Every edit
        </label>
        <button type="button" className="btn-primary" disabled={!preview || idx <= 0} onClick={restore}>
          <RotateCcw size={14} aria-hidden="true" /> Restore this version
        </button>
        <button type="button" className="icon-btn" ref={closeRef} onClick={onClose} aria-label="Close version history" title="Close (Esc)">
          <X size={18} aria-hidden="true" />
        </button>
      </header>
      <div className="history-main">
        <article className="public-body history-preview" aria-live="polite">
          {error && <p className="auth-error" role="alert">{error}</p>}
          {!error && !log && <p className="auth-hint">Decrypting history…</p>}
          {log && !versions.length && <p className="auth-hint">No versions yet. Edits appear here once they sync.</p>}
          {preview && (
            <>
              <h1 className="title">{preview.title || 'Untitled'}</h1>
              <ReadOnlyBlocks page={preview} changes={highlight ? diff?.changes : undefined} removed={highlight ? diff?.removed : undefined} />
            </>
          )}
        </article>
        <nav className="history-list" aria-label="Versions">
          {versions.map((v, i) => {
            const d = day(v.end);
            const head = d !== lastDay ? <div className="history-day">{d}</div> : null;
            lastDay = d;
            return (
              <React.Fragment key={v.seq}>
                {head}
                <button type="button" className={`history-item${v.seq === sel ? ' selected' : ''}`} aria-current={v.seq === sel} onClick={() => setSelected(v.seq)}>
                  <span className="history-time">
                    {time(v.end)}
                    {i === 0 && <span className="history-current">Current version</span>}
                  </span>
                  <span className="history-authors">
                    {v.authors.length ? (
                      v.authors.map((a) => (
                        <span key={a.id} className="history-author">
                          <span className="history-dot" style={{ background: colorFor(a.id) }} aria-hidden="true" />
                          {a.name || 'Someone'}
                        </span>
                      ))
                    ) : (
                      <span className="history-author">Deleted user</span>
                    )}
                  </span>
                  <span className="history-edits">{v.edits} {v.edits === 1 ? 'edit' : 'edits'}</span>
                </button>
              </React.Fragment>
            );
          })}
        </nav>
      </div>
    </div>
  );
};
