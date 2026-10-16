import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Check, Globe, Link2, Lock, X } from 'lucide-react';
import { ApiError, req } from '../cloud/http';
import { fingerprint, wrapPageKey } from '../cloud/crypto';
import { session, useSession } from '../cloud/session';
import { Role, sync, useSync } from '../cloud/sync';
import './cloud.css';

interface Member {
  user: { id: string; email: string; name: string };
  role: Role;
}

const ROLE_LABEL: Record<Role, string> = { owner: 'Full access', editor: 'Can edit', viewer: 'Can view' };

const initials = (name: string) =>
  name
    .split(/\s+/)
    .map((w) => w[0])
    .join('')
    .slice(0, 2)
    .toUpperCase();

export const Avatar: React.FC<{ name: string; size?: number }> = ({ name, size = 24 }) => {
  // Stable hue per name so collaborators keep their color across sessions.
  const hue = [...name].reduce((h, c) => (h * 31 + c.charCodeAt(0)) % 360, 7);
  return (
    <span className="avatar" style={{ width: size, height: size, background: `hsl(${hue} 55% 52%)` }} title={name} aria-hidden="true">
      {initials(name) || '?'}
    </span>
  );
};

export const ShareMenu: React.FC<{ pageId: string; onClose: () => void }> = ({ pageId, onClose }) => {
  const status = useSession((s) => s.status);
  const me = useSession((s) => s.user);
  const role = useSync((s) => s.roles[pageId]);
  const isPublic = useSync((s) => !!s.publicPages[pageId]);
  const [members, setMembers] = useState<Member[] | null>(null);
  const [email, setEmail] = useState('');
  const [newRole, setNewRole] = useState<'editor' | 'viewer'>('editor');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  const owner = role === 'owner';

  const load = useCallback(async () => {
    if (!role) return;
    try {
      setMembers(await req<Member[]>('GET', `/pages/${pageId}/members`));
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not load members');
    }
  }, [pageId, role]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    ref.current?.querySelector<HTMLElement>('input, button')?.focus();
    const onDown = (e: MouseEvent) => {
      // The parent also holds the toggle button, so clicking it does not close-then-reopen.
      if (ref.current && !(ref.current.parentElement ?? ref.current).contains(e.target as Node)) onClose();
    };
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose();
    document.addEventListener('mousedown', onDown);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onDown);
      document.removeEventListener('keydown', onKey);
    };
  }, [onClose]);

  const run = async (fn: () => Promise<void>) => {
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      await fn();
    } catch (e) {
      setError(e instanceof ApiError && e.status === 404 ? 'No Vellune account with that email has set up encryption yet.' : e instanceof Error ? e.message : 'Something went wrong');
    } finally {
      setBusy(false);
    }
  };

  const grant = (target: string, r: 'editor' | 'viewer') =>
    run(async () => {
      const key = sync.pageKey(pageId);
      if (!key) throw new Error('This page has not finished syncing yet.');
      const user = await req<{ id: string; email: string; public_key: JsonWebKey }>('GET', `/users/lookup?email=${encodeURIComponent(target)}`);
      await req('PUT', `/pages/${pageId}/members`, { user_id: user.id, role: r, wrapped_key: await wrapPageKey(key, user.public_key) });
      setNotice(`Shared with ${user.email}. Key fingerprint ${await fingerprint(user.public_key)}`);
      setEmail('');
      await load();
    });

  const remove = (m: Member) =>
    run(async () => {
      await req('DELETE', `/pages/${pageId}/members/${m.user.id}`);
      await load();
    });

  const togglePublic = () =>
    run(async () => {
      await req('PUT', `/pages/${pageId}/public`, { public: !isPublic });
      sync.setPublicFlag(pageId, !isPublic);
    });

  const copyLink = () =>
    run(async () => {
      await navigator.clipboard.writeText(await sync.publicLink(pageId));
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    });

  let body: React.ReactNode;
  if (status !== 'ready') {
    body = (
      <div className="share-empty">
        <Lock size={16} aria-hidden="true" />
        <p>Sign in to share pages. Sharing is end-to-end encrypted — only people you invite get the key.</p>
        <button type="button" className="auth-btn auth-btn-primary" onClick={session.showLogin}>
          Sign in
        </button>
      </div>
    );
  } else if (!role) {
    body = <div className="share-empty">This page is still syncing. Try again in a moment.</div>;
  } else {
    body = (
      <>
        {owner && (
          <form
            className="share-invite"
            onSubmit={(e) => {
              e.preventDefault();
              if (email.trim()) void grant(email.trim(), newRole);
            }}
          >
            <label htmlFor="share-email" className="sr-only">
              Invite by email
            </label>
            <input id="share-email" type="email" placeholder="Add people by email" value={email} onChange={(e) => setEmail(e.target.value)} autoComplete="off" />
            <select aria-label="Permission for new member" value={newRole} onChange={(e) => setNewRole(e.target.value as 'editor' | 'viewer')}>
              <option value="editor">Can edit</option>
              <option value="viewer">Can view</option>
            </select>
            <button type="submit" className="auth-btn auth-btn-primary share-invite-btn" disabled={busy || !email.trim()}>
              Invite
            </button>
          </form>
        )}
        {error && (
          <div className="auth-error" role="alert">
            {error}
          </div>
        )}
        {notice && (
          <div className="share-notice" role="status">
            {notice}
          </div>
        )}
        <div className="share-section-label">People with access</div>
        <ul className="share-members">
          {(members ?? []).map((m) => (
            <li key={m.user.id}>
              <Avatar name={m.user.name || m.user.email} />
              <div className="share-member-text">
                <span>
                  {m.user.name}
                  {m.user.id === me?.id ? ' (You)' : ''}
                </span>
                <small>{m.user.email}</small>
              </div>
              {owner && m.role !== 'owner' ? (
                <>
                  <select
                    aria-label={`Permission for ${m.user.email}`}
                    value={m.role}
                    disabled={busy}
                    onChange={(e) => void grant(m.user.email, e.target.value as 'editor' | 'viewer')}
                  >
                    <option value="editor">Can edit</option>
                    <option value="viewer">Can view</option>
                  </select>
                  <button type="button" className="btn-icon" aria-label={`Remove ${m.user.email}`} disabled={busy} onClick={() => void remove(m)}>
                    <X size={14} />
                  </button>
                </>
              ) : (
                <span className="share-role">{ROLE_LABEL[m.role]}</span>
              )}
            </li>
          ))}
          {members === null && <li className="share-loading">Loading…</li>}
        </ul>
        {!owner && <p className="auth-hint">Only the page owner can change who has access.</p>}

        <div className="share-public">
          <Globe size={16} aria-hidden="true" />
          <div className="share-member-text">
            <span>Publish to web</span>
            <small>{isPublic ? 'Anyone with the link can view' : 'Only invited people can access'}</small>
          </div>
          {owner && (
            <button type="button" role="switch" aria-checked={isPublic} aria-label="Publish to web" className={`switch ${isPublic ? 'on' : ''}`} disabled={busy} onClick={() => void togglePublic()}>
              <span />
            </button>
          )}
        </div>
        {isPublic && (
          <button type="button" className="auth-btn auth-btn-secondary share-copy" onClick={() => void copyLink()}>
            {copied ? <Check size={14} aria-hidden="true" /> : <Link2 size={14} aria-hidden="true" />}
            <span>{copied ? 'Copied' : 'Copy link'}</span>
          </button>
        )}
        {isPublic && <p className="auth-hint">The decryption key is in the link after “#”. It never reaches the server.</p>}
      </>
    );
  }

  return (
    <div className="share-menu popover" ref={ref} role="dialog" aria-label="Share page">
      <div className="share-head">
        <span>Share</span>
        <span className="share-e2e">
          <Lock size={12} aria-hidden="true" /> End-to-end encrypted
        </span>
      </div>
      {body}
    </div>
  );
};
