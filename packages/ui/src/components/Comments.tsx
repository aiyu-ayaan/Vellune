import React, { useEffect, useRef, useState } from 'react';
import { Check, MessageSquare, RotateCcw, Trash2 } from 'lucide-react';
import { Page, PageComment, newId } from '../model';
import { store } from '../store';
import { useSession } from '../cloud/session';
import { Avatar } from './ShareMenu';

const when = (t: number) => new Date(t).toLocaleString(undefined, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });

/** Comments on `blockId`: its first comment opens the thread, the rest are replies. */
export const threadOf = (page: Page, blockId: string) => {
  const all = (page.comments ?? []).filter((c) => c.blockId === blockId);
  const root = all.find((c) => !c.parentId);
  return { root, all };
};

export function useCommenter() {
  const user = useSession((s) => s.user);
  return { id: user?.id ?? 'local', name: user?.name || user?.email || 'You' };
}

const setComments = (pageId: string, fn: (cs: PageComment[]) => PageComment[]) =>
  store.updatePage(pageId, (p) => {
    const next = fn(p.comments ?? []);
    return { ...p, comments: next.length ? next : undefined };
  });

/** Popover thread for one block: read, reply, resolve, delete your own. */
export const CommentThread: React.FC<{ page: Page; blockId: string; readOnly: boolean; onClose: () => void }> = ({ page, blockId, readOnly, onClose }) => {
  const me = useCommenter();
  const { root, all } = threadOf(page, blockId);
  const [text, setText] = useState('');
  const ref = useRef<HTMLDivElement>(null);
  const input = useRef<HTMLTextAreaElement>(null);

  useEffect(() => {
    input.current?.focus();
    const onDown = (e: MouseEvent) => ref.current && !ref.current.contains(e.target as Node) && onClose();
    document.addEventListener('mousedown', onDown);
    return () => document.removeEventListener('mousedown', onDown);
  }, [onClose]);

  const send = () => {
    const t = text.trim();
    if (!t) return;
    const c: PageComment = { id: newId(), blockId, authorId: me.id, authorName: me.name, text: t, createdAt: Date.now(), ...(root ? { parentId: root.id } : {}) };
    // Replying to a resolved thread reopens it.
    setComments(page.id, (cs) => [...cs.map((x) => (root && x.id === root.id ? { ...x, resolved: undefined } : x)), c]);
    setText('');
  };

  return (
    <div
      ref={ref}
      className="popover comment-thread"
      role="dialog"
      aria-label="Comments"
      onKeyDown={(e) => {
        if (e.key === 'Escape') {
          e.stopPropagation();
          onClose();
        }
      }}
    >
      {root?.resolved && <div className="comment-resolved">Resolved</div>}
      {all.map((c) => (
        <div key={c.id} className="comment">
          <div className="comment-head">
            <Avatar name={c.authorName} size={20} />
            <strong>{c.authorName}</strong>
            <span className="comment-time">{when(c.createdAt)}</span>
            {!readOnly && c.id === root?.id && (
              <button
                type="button"
                className="btn-icon"
                title={c.resolved ? 'Reopen' : 'Resolve'}
                aria-label={c.resolved ? 'Reopen thread' : 'Resolve thread'}
                onClick={() => setComments(page.id, (cs) => cs.map((x) => (x.id === c.id ? { ...x, resolved: c.resolved ? undefined : true } : x)))}
              >
                {c.resolved ? <RotateCcw size={14} aria-hidden="true" /> : <Check size={14} aria-hidden="true" />}
              </button>
            )}
            {!readOnly && c.authorId === me.id && (
              <button
                type="button"
                className="btn-icon"
                title="Delete comment"
                aria-label="Delete comment"
                // Deleting the first comment removes the whole thread.
                onClick={() => setComments(page.id, (cs) => cs.filter((x) => x.id !== c.id && (c.parentId || x.parentId !== c.id)))}
              >
                <Trash2 size={14} aria-hidden="true" />
              </button>
            )}
          </div>
          <div className="comment-text">{c.text}</div>
        </div>
      ))}
      {!readOnly && (
        <div className="comment-compose">
          <textarea
            ref={input}
            rows={2}
            value={text}
            placeholder={root ? 'Reply…' : 'Add a comment…'}
            aria-label={root ? 'Reply' : 'Comment'}
            onChange={(e) => setText(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && (e.metaKey || e.ctrlKey || !e.shiftKey)) {
                e.preventDefault();
                send();
              }
            }}
          />
          <button type="button" className="btn-primary" disabled={!text.trim()} onClick={send}>
            {root ? 'Reply' : 'Comment'}
          </button>
        </div>
      )}
    </div>
  );
};

/** Small count shown at the end of a block that has an open thread. */
export const CommentBadge: React.FC<{ count: number; onClick: () => void }> = ({ count, onClick }) => (
  <button type="button" className="comment-badge" aria-label={`${count} comment${count === 1 ? '' : 's'}`} onClick={onClick}>
    <MessageSquare size={13} aria-hidden="true" />
    {count}
  </button>
);
