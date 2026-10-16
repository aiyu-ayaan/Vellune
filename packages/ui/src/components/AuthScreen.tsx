import React, { useState } from 'react';
import { Eye, EyeOff, Lock, ShieldCheck } from 'lucide-react';
import { session, useSession } from '../cloud/session';
import { signOut } from '../store';
import './cloud.css';

const GoogleMark = () => (
  <svg width="16" height="16" viewBox="0 0 48 48" aria-hidden="true">
    <path fill="#FFC107" d="M43.6 20.5H42V20H24v8h11.3C33.7 32.7 29.2 36 24 36c-6.6 0-12-5.4-12-12s5.4-12 12-12c3.1 0 5.8 1.2 7.9 3.1l5.7-5.7C34 6.1 29.3 4 24 4 12.9 4 4 12.9 4 24s8.9 20 20 20 20-8.9 20-20c0-1.3-.1-2.4-.4-3.5z" />
    <path fill="#FF3D00" d="m6.3 14.7 6.6 4.8C14.7 15.1 19 12 24 12c3.1 0 5.8 1.2 7.9 3.1l5.7-5.7C34 6.1 29.3 4 24 4 16.3 4 9.7 8.3 6.3 14.7z" />
    <path fill="#4CAF50" d="M24 44c5.2 0 9.9-2 13.4-5.2l-6.2-5.2C29.2 35.1 26.7 36 24 36c-5.2 0-9.6-3.3-11.3-8l-6.5 5C9.5 39.6 16.2 44 24 44z" />
    <path fill="#1976D2" d="M43.6 20.5H42V20H24v8h11.3c-.8 2.2-2.2 4.2-4.1 5.6l6.2 5.2C37 39.2 44 34 44 24c0-1.3-.1-2.4-.4-3.5z" />
  </svg>
);

const GitHubMark = () => (
  <svg width="16" height="16" viewBox="0 0 16 16" aria-hidden="true" fill="currentColor">
    <path d="M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27.68 0 1.36.09 2 .27 1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.013 8.013 0 0 0 16 8c0-4.42-3.58-8-8-8z" />
  </svg>
);

const PasswordField: React.FC<{
  id: string;
  label: string;
  value: string;
  onChange: (v: string) => void;
  autoComplete: string;
  hint?: string;
}> = ({ id, label, value, onChange, autoComplete, hint }) => {
  const [show, setShow] = useState(false);
  return (
    <div className="auth-field">
      <label htmlFor={id}>{label}</label>
      <div className="auth-password">
        <input
          id={id}
          type={show ? 'text' : 'password'}
          value={value}
          onChange={(e) => onChange(e.target.value)}
          autoComplete={autoComplete}
          aria-describedby={hint ? `${id}-hint` : undefined}
          required
        />
        <button type="button" className="auth-reveal" onClick={() => setShow((s) => !s)} aria-label={show ? 'Hide password' : 'Show password'} aria-pressed={show}>
          {show ? <EyeOff size={15} /> : <Eye size={15} />}
        </button>
      </div>
      {hint && (
        <div id={`${id}-hint`} className="auth-hint">
          {hint}
        </div>
      )}
    </div>
  );
};

const Shell: React.FC<{ title: string; subtitle: string; children: React.ReactNode }> = ({ title, subtitle, children }) => (
  <main className="auth-screen">
    <div className="auth-card">
      <div className="auth-logo" aria-hidden="true">
        <svg viewBox="0 0 32 32" width="40" height="40">
          <circle cx="16" cy="16" r="15" fill="none" stroke="currentColor" strokeWidth="1.6" />
          <path d="M9 10.5 16 23l7-12.5" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" />
          <circle cx="22.5" cy="8.5" r="2.2" fill="currentColor" />
        </svg>
      </div>
      <h1 className="auth-title">{title}</h1>
      <p className="auth-subtitle">{subtitle}</p>
      {children}
    </div>
  </main>
);

const ErrorLine: React.FC = () => {
  const error = useSession((s) => s.error);
  return error ? (
    <div className="auth-error" role="alert">
      {error}
    </div>
  ) : null;
};

const MIN_PASSWORD = 10;

export const LoginScreen: React.FC = () => {
  const providers = useSession((s) => s.providers);
  const reachable = useSession((s) => s.serverReachable);
  const [mode, setMode] = useState<'login' | 'signup'>('login');
  const [name, setName] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [understood, setUnderstood] = useState(false);
  const [busy, setBusy] = useState(false);
  const [localError, setLocalError] = useState<string | null>(null);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setLocalError(null);
    if (mode === 'signup' && password.length < MIN_PASSWORD) return setLocalError(`Use at least ${MIN_PASSWORD} characters.`);
    if (mode === 'signup' && !understood) return setLocalError('Please confirm you understand password recovery.');
    setBusy(true);
    try {
      if (mode === 'login') await session.login(email, password);
      else await session.signup(email, name || email.split('@')[0], password);
    } catch {
      /* message shown by ErrorLine */
    } finally {
      setBusy(false);
    }
  };

  return (
    <Shell
      title={mode === 'login' ? 'Log in to Vellune' : 'Create your Vellune account'}
      subtitle="Where thoughts take form."
    >
      {providers.length > 0 && (
        <>
          <div className="auth-providers">
            {providers.map((p) => (
              <a key={p.id} className="auth-btn auth-btn-secondary" href={`/api/v1/auth/oauth/${p.id}/start`}>
                {p.id === 'google' ? <GoogleMark /> : <GitHubMark />}
                <span>Continue with {p.name}</span>
              </a>
            ))}
          </div>
          <div className="auth-divider" role="separator">
            <span>or</span>
          </div>
        </>
      )}

      <form className="auth-form" onSubmit={submit} noValidate={false}>
        {mode === 'signup' && (
          <div className="auth-field">
            <label htmlFor="auth-name">Name</label>
            <input id="auth-name" value={name} onChange={(e) => setName(e.target.value)} autoComplete="name" maxLength={100} />
          </div>
        )}
        <div className="auth-field">
          <label htmlFor="auth-email">Email</label>
          <input id="auth-email" type="email" inputMode="email" value={email} onChange={(e) => setEmail(e.target.value)} autoComplete="email" required />
        </div>
        <PasswordField
          id="auth-password"
          label="Password"
          value={password}
          onChange={setPassword}
          autoComplete={mode === 'login' ? 'current-password' : 'new-password'}
          hint={mode === 'signup' ? `At least ${MIN_PASSWORD} characters. It also encrypts your notes.` : undefined}
        />
        {mode === 'signup' && (
          <label className="auth-check">
            <input type="checkbox" checked={understood} onChange={(e) => setUnderstood(e.target.checked)} />
            <span>I understand my password can’t be reset — if I lose it, my cloud notes can’t be decrypted.</span>
          </label>
        )}
        {localError && (
          <div className="auth-error" role="alert">
            {localError}
          </div>
        )}
        <ErrorLine />
        <button type="submit" className="auth-btn auth-btn-primary" disabled={busy || !reachable} aria-busy={busy}>
          {busy ? (mode === 'login' ? 'Unlocking…' : 'Generating keys…') : 'Continue with email'}
        </button>
        {!reachable && <div className="auth-hint">Can’t reach the Vellune server. You can still work offline.</div>}
      </form>

      <p className="auth-switch">
        {mode === 'login' ? 'New to Vellune?' : 'Already have an account?'}{' '}
        <button type="button" className="auth-link" onClick={() => setMode(mode === 'login' ? 'signup' : 'login')}>
          {mode === 'login' ? 'Create an account' : 'Log in'}
        </button>
      </p>

      <div className="auth-e2e">
        <ShieldCheck size={15} aria-hidden="true" />
        <span>End-to-end encrypted. Your password and notes are encrypted on this device before anything is synced.</span>
      </div>

      <button type="button" className="auth-link auth-local" onClick={session.continueLocal}>
        Continue without an account
      </button>
    </Shell>
  );
};

/** Signed in, but this device must unlock (or create) the encryption keys. */
export const KeyScreen: React.FC<{ mode: 'unlock' | 'setup' }> = ({ mode }) => {
  const user = useSession((s) => s.user);
  const [pass, setPass] = useState('');
  const [confirm, setConfirm] = useState('');
  const [busy, setBusy] = useState(false);
  const [localError, setLocalError] = useState<string | null>(null);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setLocalError(null);
    if (mode === 'setup') {
      if (pass.length < MIN_PASSWORD) return setLocalError(`Use at least ${MIN_PASSWORD} characters.`);
      if (pass !== confirm) return setLocalError('Passphrases do not match.');
    }
    setBusy(true);
    try {
      await (mode === 'unlock' ? session.unlock(pass) : session.setupKeys(pass));
    } catch {
      /* shown by ErrorLine */
    } finally {
      setBusy(false);
    }
  };

  return (
    <Shell
      title={mode === 'unlock' ? 'Unlock your notes' : 'Set an encryption passphrase'}
      subtitle={
        mode === 'unlock'
          ? `Signed in as ${user?.email ?? ''}. Enter your password or encryption passphrase to decrypt your notes on this device.`
          : 'Your notes are encrypted with a key only you hold. Choose a passphrase to protect it — Vellune can’t reset it for you.'
      }
    >
      <form className="auth-form" onSubmit={submit}>
        <PasswordField
          id="key-pass"
          label={mode === 'unlock' ? 'Password or passphrase' : 'Encryption passphrase'}
          value={pass}
          onChange={setPass}
          autoComplete={mode === 'unlock' ? 'current-password' : 'new-password'}
        />
        {mode === 'setup' && <PasswordField id="key-confirm" label="Confirm passphrase" value={confirm} onChange={setConfirm} autoComplete="new-password" />}
        {localError && (
          <div className="auth-error" role="alert">
            {localError}
          </div>
        )}
        <ErrorLine />
        <button type="submit" className="auth-btn auth-btn-primary" disabled={busy} aria-busy={busy}>
          <Lock size={14} aria-hidden="true" />
          <span>{busy ? 'Deriving keys…' : mode === 'unlock' ? 'Unlock' : 'Create keys'}</span>
        </button>
      </form>
      <button type="button" className="auth-link auth-local" onClick={() => void signOut()}>
        Sign out
      </button>
    </Shell>
  );
};
