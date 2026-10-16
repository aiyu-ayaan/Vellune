import React, { useEffect, useState } from 'react';
import { Lock } from 'lucide-react';
import { Page } from '../model';
import { openPublicPage } from '../cloud/sync';
import './cloud.css';

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
            {page.blocks.map((b) => {
              const cls = `block${b.color ? ` nc-${b.color}` : ''}`;
              switch (b.kind) {
                case 'heading': {
                  const H = `h${Math.min(3, Math.max(1, b.level ?? 1)) + 1}` as 'h2' | 'h3' | 'h4';
                  return <H key={b.id} className={cls}>{b.text}</H>;
                }
                case 'todo':
                  return (
                    <div key={b.id} className={cls}>
                      <input type="checkbox" checked={!!b.checked} readOnly aria-label={b.checked ? 'Done' : 'Not done'} /> {b.text}
                    </div>
                  );
                case 'bullet':
                  return <div key={b.id} className={cls}>• {b.text}</div>;
                case 'quote':
                  return <blockquote key={b.id} className={cls}>{b.text}</blockquote>;
                case 'code':
                  return <pre key={b.id}><code>{b.text}</code></pre>;
                case 'divider':
                  return <hr key={b.id} />;
                default:
                  return <p key={b.id} className={cls}>{b.text}</p>;
              }
            })}
          </>
        )}
      </article>
    </div>
  );
};
