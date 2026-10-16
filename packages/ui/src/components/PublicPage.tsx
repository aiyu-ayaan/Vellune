import React, { useEffect, useState } from 'react';
import { Lock } from 'lucide-react';
import { Block, Page } from '../model';
import { openPublicPage } from '../cloud/sync';
import { parseInline } from '../inline';
import { highlightCode } from '../highlight';
import './cloud.css';

/** Inline marks for readers; wikilinks show as text because linked pages are not public. */
const rich = (text: string) =>
  parseInline(text).map((t, k) => {
    if (t.type === 'bold') return <strong key={k}>{t.text}</strong>;
    if (t.type === 'italic') return <em key={k}>{t.text}</em>;
    if (t.type === 'strike') return <s key={k}>{t.text}</s>;
    if (t.type === 'code') return <code key={k}>{t.text}</code>;
    if (t.type === 'link' || t.type === 'url') return <a key={k} href={t.href} target="_blank" rel="noopener noreferrer nofollow">{t.text}</a>;
    return <React.Fragment key={k}>{t.text}</React.Fragment>;
  });

/** Removed blocks re-inserted after the nearest earlier block that still exists, for diffs. */
function withRemoved(blocks: Block[], removed: { block: Block; after: string | null }[] = []): Block[] {
  const out = [...blocks];
  for (const r of removed) {
    const at = r.after ? out.findIndex((b) => b.id === r.after) + 1 : 0;
    out.splice(at, 0, r.block);
  }
  return out;
}

/** Read-only page body. `changes`/`removed` mark a version-history diff. */
export const ReadOnlyBlocks: React.FC<{
  page: Page;
  changes?: Record<string, 'added' | 'changed'>;
  removed?: { block: Block; after: string | null }[];
}> = ({ page, changes, removed }) => {
  const removedIds = new Set((removed ?? []).map((r) => r.block.id));
  const shown = withRemoved(page.blocks, removed);
  return (
    <>
      {shown.map((b, i) => {
              const t = rich(b.text);
              const mark = removedIds.has(b.id) ? ' diff-removed' : changes?.[b.id] ? ` diff-${changes[b.id]}` : '';
              const cls = `block${b.color ? ` nc-${b.color}` : ''}${mark}`;
              switch (b.kind) {
                case 'heading': {
                  const H = `h${Math.min(3, Math.max(1, b.level ?? 1)) + 1}` as 'h2' | 'h3' | 'h4';
                  return <H key={b.id} className={cls}>{t}</H>;
                }
                case 'todo':
                  return (
                    <div key={b.id} className={cls}>
                      <input type="checkbox" checked={!!b.checked} readOnly aria-label={b.checked ? 'Done' : 'Not done'} /> {t}
                    </div>
                  );
                case 'bullet':
                  return <div key={b.id} className={cls}>• {t}</div>;
                case 'numbered': {
                  let n = 1;
                  while (shown[i - n]?.kind === 'numbered') n++;
                  return <div key={b.id} className={cls}>{n}. {t}</div>;
                }
                case 'quote':
                  return <blockquote key={b.id} className={cls}>{t}</blockquote>;
                case 'callout':
                  return <aside key={b.id} className={`${cls} public-callout`}>{t}</aside>;
                case 'code':
                  return <pre key={b.id} className={`public-code${mark}`}><code dangerouslySetInnerHTML={{ __html: highlightCode(b.text, b.language) }} /></pre>;
                case 'divider':
                  return <hr key={b.id} className={mark.trim() || undefined} />;
                default:
                  return <p key={b.id} className={cls}>{t}</p>;
              }
            })}
    </>
  );
};

/** Read-only, live-updating view of a published page at /share/{id}#k={key}. */
export const PublicPage: React.FC<{ pageId: string; rawKey: string }> = ({ pageId, rawKey }) => {
  const [page, setPage] = useState<Page | null>(null);
  const [error, setError] = useState<string | null>(rawKey ? null : 'This link is missing its decryption key.');

  useEffect(() => {
    if (!rawKey) return;
    return openPublicPage(pageId, rawKey, (p, err) => {
      if (err) setError(err);
      else if (p) {
        setPage(p);
        setError(null);
        document.title = `${p.title || 'Untitled'} · Vellune`;
      }
    });
  }, [pageId, rawKey]);

  return (
    <div className="public-page">
      <div className="public-bar">
        <span>Vellune</span>
        <span style={{ display: 'inline-flex', gap: 6, alignItems: 'center' }}>
          <Lock size={12} aria-hidden="true" /> End-to-end encrypted · read only
        </span>
      </div>
      <article className="public-body">
        {error && (
          <div className="auth-error" role="alert">
            {error}
          </div>
        )}
        {!page && !error && <p className="auth-hint">Decrypting…</p>}
        {page && (
          <>
            <h1 className="title">{page.title || 'Untitled'}</h1>
            <ReadOnlyBlocks page={page} />
          </>
        )}
      </article>
    </div>
  );
};
