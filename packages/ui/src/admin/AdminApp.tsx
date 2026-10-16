import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Activity, ArrowLeft, Check, Copy, KeyRound, LayoutDashboard, Settings, Users } from 'lucide-react';
import './admin.css';
import {
  adminApi,
  ApiError,
  type AdminStats,
  type AdminUser,
  type AuditEvent,
  type OAuthProviderConfig,
  type ServerSettings,
  type User,
} from './api';

type Section = 'overview' | 'users' | 'auth' | 'settings' | 'audit';
type Toast = (msg: string) => void;

const NAV: { id: Section; label: string; icon: React.ReactNode }[] = [
  { id: 'overview', label: 'Overview', icon: <LayoutDashboard size={16} aria-hidden /> },
  { id: 'users', label: 'Users', icon: <Users size={16} aria-hidden /> },
  { id: 'auth', label: 'Authentication', icon: <KeyRound size={16} aria-hidden /> },
  { id: 'settings', label: 'Server settings', icon: <Settings size={16} aria-hidden /> },
  { id: 'audit', label: 'Audit log', icon: <Activity size={16} aria-hidden /> },
];

const errMsg = (e: unknown) => (e instanceof ApiError ? e.message : 'Something went wrong.');
const fmtDate = (s: string) => new Date(s).toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' });
const fmtDateTime = (s: string) => new Date(s).toLocaleString();
const MIN_BYTES = 1024;
const MAX_BYTES = 8 * 1024 * 1024;
function fmtBytes(n: number) {
  if (n >= 1024 * 1024) return `${(n / 1024 / 1024).toFixed(n % (1024 * 1024) ? 2 : 0)} MiB`;
  if (n >= 1024) return `${(n / 1024).toFixed(n % 1024 ? 1 : 0)} KiB`;
  return `${n} B`;
}

/* ---------- small shared pieces ---------- */

function useLoad<T>(fn: () => Promise<T>) {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const fnRef = useRef(fn);
  fnRef.current = fn;
  const reload = useCallback(() => {
    setLoading(true);
    setError(null);
    return fnRef.current().then(
      (d) => { setData(d); setLoading(false); },
      (e) => { setError(errMsg(e)); setLoading(false); },
    );
  }, []);
  useEffect(() => { void reload(); }, [reload]);
  return { data, setData, error, loading, reload };
}

const Skeleton: React.FC<{ h?: number; w?: string }> = ({ h = 16, w = '100%' }) => (
  <div className="adm-skel" style={{ height: h, width: w }} aria-hidden />
);

const SkeletonRows: React.FC<{ n?: number }> = ({ n = 5 }) => (
  <div className="adm-stack" role="status" aria-label="Loading">
    {Array.from({ length: n }, (_, i) => <Skeleton key={i} h={36} />)}
  </div>
);

const ErrorBanner: React.FC<{ message: string; onRetry?: () => void }> = ({ message, onRetry }) => (
  <div className="adm-banner" role="alert">
    <span>{message}</span>
    {onRetry && <button className="adm-btn adm-btn-sm" onClick={onRetry}>Retry</button>}
  </div>
);

const Switch: React.FC<{ checked: boolean; onChange: (v: boolean) => void; label: string }> = ({ checked, onChange, label }) => (
  <button type="button" role="switch" aria-checked={checked} aria-label={label} className="adm-switch" onClick={() => onChange(!checked)} />
);

const Header: React.FC<{ title: string; sub: string; right?: React.ReactNode }> = ({ title, sub, right }) => (
  <div className="adm-card-head" style={{ alignItems: 'flex-start' }}>
    <div>
      <h1 className="adm-h1">{title}</h1>
      <p className="adm-sub" style={{ marginBottom: 0 }}>{sub}</p>
    </div>
    {right}
  </div>
);

interface ConfirmState { title: string; body: string; action: string; danger?: boolean; run: () => Promise<void> }

const ConfirmModal: React.FC<{ state: ConfirmState; onClose: () => void }> = ({ state, onClose }) => {
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const prev = document.activeElement as HTMLElement | null;
    ref.current?.querySelector<HTMLElement>('[data-autofocus]')?.focus();
    return () => prev?.focus?.();
  }, []);
  const go = async () => {
    setBusy(true);
    setErr(null);
    try { await state.run(); onClose(); } catch (e) { setErr(errMsg(e)); setBusy(false); }
  };
  const onKey = (e: React.KeyboardEvent) => {
    if (e.key === 'Escape') { e.stopPropagation(); if (!busy) onClose(); return; }
    if (e.key === 'Tab' && ref.current) {
      const f = ref.current.querySelectorAll<HTMLElement>('button:not(:disabled)');
      if (!f.length) return;
      const first = f[0], last = f[f.length - 1];
      if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
      else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
    }
  };
  return (
    <div className="adm-overlay" onMouseDown={(e) => { if (e.target === e.currentTarget && !busy) onClose(); }}>
      <div ref={ref} className="adm-modal" role="dialog" aria-modal="true" aria-labelledby="adm-modal-title" onKeyDown={onKey}>
        <h2 id="adm-modal-title">{state.title}</h2>
        <p>{state.body}</p>
        {err && <div className="adm-banner" role="alert"><span>{err}</span></div>}
        <div className="adm-modal-actions">
          <button className="adm-btn" onClick={onClose} disabled={busy}>Cancel</button>
          <button data-autofocus className={`adm-btn ${state.danger ? 'adm-btn-danger' : 'adm-btn-primary'}`} onClick={go} disabled={busy}>
            {busy ? 'Working…' : state.action}
          </button>
        </div>
      </div>
    </div>
  );
};

/* ---------- Overview ---------- */

const Overview: React.FC = () => {
  const { data, error, loading, reload } = useLoad<AdminStats>(adminApi.stats);
  const cards: [string, React.ReactNode][] = data
    ? [
        ['Users', data.users],
        ['Admins', data.admins],
        ['Disabled users', data.disabled_users],
        ['Pages', data.pages],
        ['Updates', data.updates],
        ['Stored updates', fmtBytes(data.update_bytes)],
        ['Active sessions', data.active_sessions],
        ['Online now', data.online_connections],
      ]
    : [];
  return (
    <>
      <Header title="Overview" sub="Server activity at a glance." right={<button className="adm-btn" onClick={reload}>Refresh</button>} />
      {error && <ErrorBanner message={error} onRetry={reload} />}
      {loading && !data ? (
        <div className="adm-grid" role="status" aria-label="Loading">{Array.from({ length: 8 }, (_, i) => <Skeleton key={i} h={82} />)}</div>
      ) : (
        <div className="adm-grid">
          {cards.map(([l, v]) => (
            <div className="adm-card" key={l}>
              <div className="adm-stat-label">{l}</div>
              <div className="adm-stat-value">{v}</div>
            </div>
          ))}
        </div>
      )}
    </>
  );
};

/* ---------- Users ---------- */

const PAGE = 25;

const UsersSection: React.FC<{ me: User; toast: Toast; confirm: (c: ConfirmState) => void }> = ({ me, toast, confirm }) => {
  const [q, setQ] = useState('');
  const [dq, setDq] = useState('');
  const [offset, setOffset] = useState(0);
  const [pending, setPending] = useState<string | null>(null);
  const [actErr, setActErr] = useState<string | null>(null);

  useEffect(() => {
    const t = setTimeout(() => { setDq(q.trim()); setOffset(0); }, 300);
    return () => clearTimeout(t);
  }, [q]);

  const { data, error, loading, reload } = useLoad(() => adminApi.users(dq, offset, PAGE));
  const reloadRef = useRef(reload);
  reloadRef.current = reload;
  useEffect(() => { void reloadRef.current(); }, [dq, offset]);

  const patch = async (u: AdminUser, p: { is_admin?: boolean; disabled?: boolean }, msg: string) => {
    setPending(u.id);
    setActErr(null);
    try { await adminApi.updateUser(u.id, p); toast(msg); await reload(); }
    catch (e) { setActErr(errMsg(e)); }
    finally { setPending(null); }
  };

  const total = data?.total ?? 0;
  const users = data?.users ?? [];

  return (
    <>
      <Header title="Users" sub="Manage accounts, roles, and sessions." />
      <div className="adm-toolbar">
        <label className="adm-label" htmlFor="adm-user-q" style={{ position: 'absolute', width: 1, height: 1, overflow: 'hidden', clip: 'rect(0 0 0 0)' }}>Search users</label>
        <input id="adm-user-q" className="adm-input" type="search" placeholder="Search by email or name" value={q} onChange={(e) => setQ(e.target.value)} />
      </div>
      {error && <ErrorBanner message={error} onRetry={reload} />}
      {actErr && <ErrorBanner message={actErr} />}
      {loading && !data ? <SkeletonRows /> : users.length === 0 && !error ? (
        <div className="adm-empty">{dq ? `No users match “${dq}”.` : 'No users yet.'}</div>
      ) : (
        <div className="adm-table-wrap" style={{ opacity: loading ? 0.6 : 1, transition: 'opacity 150ms ease-out' }}>
          <table className="adm-table">
            <thead>
              <tr><th>User</th><th>Providers</th><th>Pages</th><th>Status</th><th>Keys</th><th>Created</th><th><span className="sr-only" style={{ position: 'absolute', width: 1, height: 1, overflow: 'hidden', clip: 'rect(0 0 0 0)' }}>Actions</span></th></tr>
            </thead>
            <tbody>
              {users.map((u) => {
                const self = u.id === me.id;
                const busy = pending === u.id;
                return (
                  <tr key={u.id}>
                    <td>
                      <div className="adm-user-name">{u.name || '—'}{self && <span className="adm-badge" style={{ marginLeft: 6 }}>You</span>}</div>
                      <div className="adm-user-email">{u.email}</div>
                    </td>
                    <td><div className="adm-badges">{u.providers.length ? u.providers.map((p) => <span key={p} className="adm-badge">{p}</span>) : <span className="adm-badge">password</span>}</div></td>
                    <td className="adm-num">{u.pages}</td>
                    <td><div className="adm-badges">
                      {u.is_admin && <span className="adm-badge adm-badge-accent">Admin</span>}
                      {u.disabled && <span className="adm-badge adm-badge-danger">Disabled</span>}
                      {!u.is_admin && !u.disabled && <span className="adm-badge">Member</span>}
                    </div></td>
                    <td>{u.has_keys ? <span className="adm-badge adm-badge-success">Keys set</span> : <span className="adm-badge">No keys</span>}</td>
                    <td className="adm-num" style={{ whiteSpace: 'nowrap' }}>{fmtDate(u.created_at)}</td>
                    <td>
                      <div className="adm-actions">
                        <button className="adm-btn adm-btn-sm" disabled={busy || (self && u.is_admin)} title={self && u.is_admin ? 'You cannot demote yourself' : undefined}
                          onClick={() => patch(u, { is_admin: !u.is_admin }, u.is_admin ? 'Admin removed' : 'Made admin')}>
                          {u.is_admin ? 'Remove admin' : 'Make admin'}
                        </button>
                        <button className="adm-btn adm-btn-sm" disabled={busy || self} title={self ? 'You cannot disable yourself' : undefined}
                          onClick={() => u.disabled
                            ? patch(u, { disabled: false }, 'User enabled')
                            : confirm({ title: 'Disable user?', body: `${u.email} will be signed out and unable to sign in.`, action: 'Disable', danger: true,
                                run: async () => { await adminApi.updateUser(u.id, { disabled: true }); toast('User disabled'); await reload(); } })}>
                          {u.disabled ? 'Enable' : 'Disable'}
                        </button>
                        <button className="adm-btn adm-btn-sm" disabled={busy}
                          onClick={() => confirm({ title: 'Sign out everywhere?', body: `All active sessions for ${u.email} will end.`, action: 'Sign out',
                            run: async () => { await adminApi.revokeSessions(u.id); toast('Sessions revoked'); } })}>
                          Sign out everywhere
                        </button>
                        <button className="adm-btn adm-btn-sm adm-btn-danger" disabled={busy || self} title={self ? 'You cannot delete yourself' : undefined}
                          onClick={() => confirm({ title: 'Delete user?', body: `${u.email} and all pages they own will be permanently deleted. This cannot be undone.`, action: 'Delete', danger: true,
                            run: async () => { await adminApi.deleteUser(u.id); toast('User deleted'); await reload(); } })}>
                          Delete
                        </button>
                      </div>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
      {total > 0 && (
        <div className="adm-pager">
          <span className="adm-num">{offset + 1}–{Math.min(offset + PAGE, total)} of {total}</span>
          <div className="adm-actions">
            <button className="adm-btn adm-btn-sm" disabled={offset === 0 || loading} onClick={() => setOffset(Math.max(0, offset - PAGE))}>Previous</button>
            <button className="adm-btn adm-btn-sm" disabled={offset + PAGE >= total || loading} onClick={() => setOffset(offset + PAGE)}>Next</button>
          </div>
        </div>
      )}
    </>
  );
};

/* ---------- Authentication ---------- */

const PROVIDER_META = {
  github: { label: 'GitHub', url: 'https://github.com/settings/developers', link: 'GitHub developer settings',
    steps: ['Open GitHub developer settings and create a new OAuth App.', 'Paste the redirect URI below as the Authorization callback URL.', 'Copy the Client ID and a new Client secret here.'] },
  google: { label: 'Google (Gmail)', url: 'https://console.cloud.google.com/apis/credentials', link: 'Google Cloud credentials',
    steps: ['Open Google Cloud credentials and create an OAuth client ID (Web application).', 'Add the redirect URI below under Authorized redirect URIs.', 'Copy the Client ID and Client secret here.'] },
} as const;

const ProviderCard: React.FC<{ cfg: OAuthProviderConfig; toast: Toast }> = ({ cfg, toast }) => {
  const meta = PROVIDER_META[cfg.provider];
  const [enabled, setEnabled] = useState(cfg.enabled);
  const [clientId, setClientId] = useState(cfg.client_id);
  const [secret, setSecret] = useState('');
  const [hasSecret, setHasSecret] = useState(cfg.has_secret);
  const [state, setState] = useState<'idle' | 'saving' | 'saved' | 'error'>('idle');
  const [msg, setMsg] = useState('');
  const [copied, setCopied] = useState(false);
  const idErr = enabled && (!clientId.trim() || (!hasSecret && !secret)) ? 'Client ID and client secret are required to enable this provider.' : null;
  const [tried, setTried] = useState(false);
  const idBase = `adm-${cfg.provider}`;

  const save = async () => {
    setTried(true);
    if (idErr) return;
    setState('saving');
    try {
      const out = await adminApi.updateOAuth(cfg.provider, { enabled, client_id: clientId.trim(), ...(secret ? { client_secret: secret } : {}) });
      setEnabled(out.enabled); setClientId(out.client_id); setHasSecret(out.has_secret); setSecret(''); setTried(false);
      setState('saved'); toast(`${meta.label} saved`);
    } catch (e) { setMsg(errMsg(e)); setState('error'); }
  };
  const copy = async () => {
    try { await navigator.clipboard.writeText(cfg.redirect_uri); setCopied(true); setTimeout(() => setCopied(false), 1500); }
    catch { toast('Copy failed. Select the text manually.'); }
  };
  return (
    <section className="adm-card" aria-labelledby={`${idBase}-t`}>
      <div className="adm-card-head">
        <h2 className="adm-card-title" id={`${idBase}-t`}>{meta.label}</h2>
        <div className="adm-switch-row">
          <span aria-hidden>{enabled ? 'Enabled' : 'Disabled'}</span>
          <Switch checked={enabled} onChange={(v) => { setEnabled(v); setState('idle'); }} label={`Enable ${meta.label}`} />
        </div>
      </div>
      <ol className="adm-steps">
        {meta.steps.map((s, i) => <li key={i}>{i === 0 ? <>{s.replace(/ and create.*$/, '')} — <a href={meta.url} target="_blank" rel="noreferrer">{meta.link}</a></> : s}</li>)}
      </ol>
      <div className="adm-field">
        <label className="adm-label" htmlFor={`${idBase}-ru`}>Redirect URI</label>
        <div className="adm-row">
          <input id={`${idBase}-ru`} className="adm-input" readOnly value={cfg.redirect_uri} onFocus={(e) => e.currentTarget.select()} />
          <button type="button" className="adm-btn" onClick={copy} aria-label="Copy redirect URI">
            {copied ? <Check size={14} aria-hidden /> : <Copy size={14} aria-hidden />}{copied ? 'Copied' : 'Copy'}
          </button>
        </div>
      </div>
      <div className="adm-field">
        <label className="adm-label" htmlFor={`${idBase}-id`}>Client ID</label>
        <input id={`${idBase}-id`} className="adm-input" value={clientId} autoComplete="off" aria-invalid={tried && !!idErr && !clientId.trim()} onChange={(e) => { setClientId(e.target.value); setState('idle'); }} />
      </div>
      <div className="adm-field">
        <label className="adm-label" htmlFor={`${idBase}-sec`}>Client secret</label>
        <input id={`${idBase}-sec`} className="adm-input" type="password" autoComplete="new-password" value={secret}
          placeholder={hasSecret ? '•••••• stored' : ''} aria-invalid={tried && !!idErr && !hasSecret && !secret}
          onChange={(e) => { setSecret(e.target.value); setState('idle'); }} />
        <span className="adm-help">{hasSecret ? 'Leave blank to keep the stored secret.' : 'Not set yet.'}</span>
      </div>
      {tried && idErr && <div className="adm-err" role="alert" style={{ marginBottom: 12 }}>{idErr}</div>}
      <div className="adm-save-row">
        <button className="adm-btn adm-btn-primary" onClick={save} disabled={state === 'saving'}>{state === 'saving' ? 'Saving…' : 'Save'}</button>
        <span className={`adm-status ${state === 'saved' ? 'adm-status-ok' : 'adm-status-err'}`} role="status">
          {state === 'saved' ? 'Saved' : state === 'error' ? msg : ''}
        </span>
      </div>
    </section>
  );
};

const AuthSection: React.FC<{ toast: Toast }> = ({ toast }) => {
  const { data, error, loading, reload } = useLoad(adminApi.oauth);
  return (
    <>
      <Header title="Authentication" sub="Let people sign in with GitHub or Google." />
      {error && <ErrorBanner message={error} onRetry={reload} />}
      {loading && !data ? <SkeletonRows n={2} /> : (
        <div className="adm-stack">{data?.map((c) => <ProviderCard key={c.provider} cfg={c} toast={toast} />)}</div>
      )}
    </>
  );
};

/* ---------- Server settings ---------- */

const parseDomains = (s: string) =>
  Array.from(new Set(s.split(/[,\n]/).map((d) => d.trim().toLowerCase().replace(/^@/, '')).filter(Boolean)));

const SettingsForm: React.FC<{ initial: ServerSettings; toast: Toast }> = ({ initial, toast }) => {
  const [signups, setSignups] = useState(initial.allow_signups);
  const [domains, setDomains] = useState(initial.allowed_email_domains.join('\n'));
  const [appUrl, setAppUrl] = useState(initial.app_url);
  const [bytes, setBytes] = useState(String(initial.max_update_bytes));
  const [state, setState] = useState<'idle' | 'saving' | 'saved' | 'error'>('idle');
  const [msg, setMsg] = useState('');
  const n = Number(bytes);
  const valid = Number.isFinite(n) && bytes.trim() !== '';
  const clamped = Math.min(MAX_BYTES, Math.max(MIN_BYTES, Math.round(n)));
  const list = parseDomains(domains);
  const dirty = () => setState('idle');

  const save = async () => {
    if (!valid) return;
    setState('saving');
    try {
      const out = await adminApi.updateSettings({ allow_signups: signups, allowed_email_domains: list, app_url: appUrl.trim(), max_update_bytes: clamped });
      setSignups(out.allow_signups); setDomains(out.allowed_email_domains.join('\n')); setAppUrl(out.app_url); setBytes(String(out.max_update_bytes));
      setState('saved'); toast('Settings saved');
    } catch (e) { setMsg(errMsg(e)); setState('error'); }
  };
  return (
    <div className="adm-card">
      <div className="adm-field">
        <div className="adm-switch-row">
          <Switch checked={signups} onChange={(v) => { setSignups(v); dirty(); }} label="Allow signups" />
          <span className="adm-label">Allow signups</span>
        </div>
        <span className="adm-help">When off, only existing users can sign in.</span>
      </div>
      <div className="adm-field">
        <label className="adm-label" htmlFor="adm-domains">Allowed email domains</label>
        <textarea id="adm-domains" className="adm-textarea" value={domains} placeholder="example.com" onChange={(e) => { setDomains(e.target.value); dirty(); }} />
        <span className="adm-help">Separate with commas or new lines. Leave empty to allow any domain.</span>
        {list.length > 0 && <div className="adm-chips" aria-label="Parsed domains">{list.map((d) => <span key={d} className="adm-badge adm-badge-accent">{d}</span>)}</div>}
      </div>
      <div className="adm-field">
        <label className="adm-label" htmlFor="adm-appurl">App URL</label>
        <input id="adm-appurl" className="adm-input" type="url" value={appUrl} placeholder="http://localhost:3000" onChange={(e) => { setAppUrl(e.target.value); dirty(); }} />
        <span className="adm-help">Public address of this app. Used for OAuth redirects and cookie security.</span>
      </div>
      <div className="adm-field">
        <label className="adm-label" htmlFor="adm-bytes">Max update size (bytes)</label>
        <input id="adm-bytes" className="adm-input" type="number" min={MIN_BYTES} max={MAX_BYTES} step={1024} value={bytes} aria-invalid={!valid} onChange={(e) => { setBytes(e.target.value); dirty(); }} />
        <span className="adm-help">
          {valid ? `${fmtBytes(clamped)} per pushed update${clamped !== n ? ` (clamped from ${fmtBytes(Math.max(0, n))})` : ''}. ` : 'Enter a number. '}
          Allowed range 1 KiB to 8 MiB.
        </span>
      </div>
      <div className="adm-save-row">
        <button className="adm-btn adm-btn-primary" onClick={save} disabled={state === 'saving' || !valid}>{state === 'saving' ? 'Saving…' : 'Save'}</button>
        <span className={`adm-status ${state === 'saved' ? 'adm-status-ok' : 'adm-status-err'}`} role="status">
          {state === 'saved' ? 'Saved' : state === 'error' ? msg : ''}
        </span>
      </div>
    </div>
  );
};

const SettingsSection: React.FC<{ toast: Toast }> = ({ toast }) => {
  const { data, error, loading, reload } = useLoad(adminApi.settings);
  return (
    <>
      <Header title="Server settings" sub="Signup policy and limits." />
      {error && <ErrorBanner message={error} onRetry={reload} />}
      {loading && !data ? <SkeletonRows n={4} /> : data && <SettingsForm initial={data} toast={toast} />}
    </>
  );
};

/* ---------- Audit ---------- */

const AuditSection: React.FC = () => {
  const { data, error, loading, reload } = useLoad<AuditEvent[]>(() => adminApi.audit(100));
  return (
    <>
      <Header title="Audit log" sub="Security-relevant events, newest first." right={<button className="adm-btn" onClick={reload} disabled={loading}>Refresh</button>} />
      {error && <ErrorBanner message={error} onRetry={reload} />}
      {loading && !data ? <SkeletonRows /> : data && data.length === 0 ? (
        <div className="adm-empty">No events recorded yet.</div>
      ) : data && (
        <div className="adm-table-wrap">
          <table className="adm-table">
            <thead><tr><th>Time</th><th>Actor</th><th>Action</th><th>Target</th></tr></thead>
            <tbody>
              {data.map((e) => (
                <tr key={e.id}>
                  <td className="adm-num" style={{ whiteSpace: 'nowrap' }}>{fmtDateTime(e.created_at)}</td>
                  <td>{e.actor_email ?? <span className="adm-user-email">system</span>}</td>
                  <td><span className="adm-badge">{e.action}</span></td>
                  <td className="adm-user-email">{e.target ?? '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  );
};

/* ---------- Shell ---------- */

export const AdminApp: React.FC<{ onExit?: () => void }> = ({ onExit }) => {
  const [me, setMe] = useState<User | null>(null);
  const [gate, setGate] = useState<'loading' | 'unauth' | 'forbidden' | 'error' | 'ok'>('loading');
  const [gateErr, setGateErr] = useState('');
  const [section, setSection] = useState<Section>('overview');
  const [confirmState, setConfirm] = useState<ConfirmState | null>(null);
  const [toastMsg, setToastMsg] = useState<string | null>(null);
  const toastTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);

  const toast = useCallback<Toast>((m) => {
    setToastMsg(m);
    clearTimeout(toastTimer.current);
    toastTimer.current = setTimeout(() => setToastMsg(null), 3000);
  }, []);
  useEffect(() => () => clearTimeout(toastTimer.current), []);

  const loadMe = useCallback(() => {
    setGate('loading');
    adminApi.me().then(
      ({ user }) => { setMe(user); setGate(user.is_admin ? 'ok' : 'forbidden'); },
      (e) => {
        if (e instanceof ApiError && e.status === 401) setGate('unauth');
        else { setGateErr(errMsg(e)); setGate('error'); }
      },
    );
  }, []);
  useEffect(loadMe, [loadMe]);

  if (gate === 'loading') return <div className="adm-root"><div className="adm-center" style={{ width: '100%' }} role="status">Loading…</div></div>;
  if (gate !== 'ok' || !me) {
    return (
      <div className="adm-root">
        <div className="adm-center" style={{ width: '100%' }}>
          <h1 className="adm-h1" style={{ fontSize: 18 }}>
            {gate === 'unauth' ? 'Sign in required' : gate === 'forbidden' ? 'Admin access required' : 'Could not load'}
          </h1>
          {gate === 'error' && <p className="adm-err">{gateErr}</p>}
          {gate === 'error' && <button className="adm-btn" onClick={loadMe}>Retry</button>}
          {onExit && <button className="adm-btn adm-btn-primary" onClick={onExit}>{gate === 'unauth' ? 'Go to sign in' : 'Back to app'}</button>}
        </div>
      </div>
    );
  }

  return (
    <div className="adm-root">
      <nav className="adm-nav" aria-label="Admin sections">
        <div className="adm-nav-title">Admin</div>
        {NAV.map((n) => (
          <button key={n.id} className="adm-nav-item" aria-current={section === n.id ? 'page' : undefined} onClick={() => setSection(n.id)}>
            {n.icon}{n.label}
          </button>
        ))}
        <div className="adm-nav-spacer" />
        {onExit && <button className="adm-nav-item" onClick={onExit}><ArrowLeft size={16} aria-hidden />Back to app</button>}
      </nav>
      <main className="adm-main">
        <div className="adm-content">
          {section === 'overview' && <Overview />}
          {section === 'users' && <UsersSection me={me} toast={toast} confirm={setConfirm} />}
          {section === 'auth' && <AuthSection toast={toast} />}
          {section === 'settings' && <SettingsSection toast={toast} />}
          {section === 'audit' && <AuditSection />}
        </div>
      </main>
      {confirmState && <ConfirmModal state={confirmState} onClose={() => setConfirm(null)} />}
      {toastMsg && <div className="adm-toast" role="status">{toastMsg}</div>}
    </div>
  );
};
