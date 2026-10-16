// Account session + E2E identity. The unlocked private key is a non-extractable CryptoKey kept in
// IndexedDB so reloads stay signed in; logout deletes it along with all local synced data.

import { useSyncExternalStore } from 'react';
import { ApiError, req } from './http';
import { Identity, KeyBundle, createKeys, derive, unb64, unlockKeys } from './crypto';
import { idbClear, idbGet, idbPut } from './idb';

export interface User {
  id: string;
  email: string;
  name: string;
  is_admin: boolean;
  created_at: string;
}

export interface Provider {
  id: 'github' | 'google';
  name: string;
}

/**
 * loading    – checking the server
 * signed_out – show the login screen
 * local      – user chose to work without an account (local-only, nothing leaves the device)
 * needs_keys – OAuth account without an encryption passphrase yet
 * locked     – signed in, but this device has no unlocked private key
 * ready      – signed in and unlocked; sync runs
 */
export type Status = 'loading' | 'signed_out' | 'local' | 'needs_keys' | 'locked' | 'ready';

export interface SessionState {
  status: Status;
  user: User | null;
  bundle: KeyBundle | null;
  identity: Identity | null;
  providers: Provider[];
  serverReachable: boolean;
  error: string | null;
}

const LOCAL_FLAG = 'vellune:local-only';

const AUTH_ERRORS: Record<string, string> = {
  oauth_cancelled: 'Sign-in was cancelled.',
  oauth_state: 'Sign-in expired or was started in another browser. Please try again.',
  oauth_failed: 'The provider did not complete sign-in. Please try again.',
  oauth_disabled: 'That sign-in method is turned off.',
  oauth_no_verified_email: 'Your account needs a verified email address.',
  signups_disabled: 'New sign-ups are disabled on this server.',
  account_disabled: 'This account has been disabled by an administrator.',
  oauth_email_taken: 'An account with this email already uses a password. Log in with your email and password.',
};

interface StoredIdentity extends Identity {
  userId: string;
}

class Session {
  private state: SessionState = {
    status: 'loading',
    user: null,
    bundle: null,
    identity: null,
    providers: [],
    serverReachable: true,
    error: null,
  };
  private listeners = new Set<() => void>();
  /** Set by the app: wipe in-memory pages/docs when a different account signs in on this device. */
  onAccountSwitch: (() => void) | null = null;

  get = () => this.state;
  subscribe = (l: () => void) => {
    this.listeners.add(l);
    return () => this.listeners.delete(l);
  };
  private set(p: Partial<SessionState>) {
    this.state = { ...this.state, ...p };
    this.listeners.forEach((l) => l());
  }

  async init() {
    const url = new URL(location.href);
    const authError = url.searchParams.get('auth_error');
    if (authError) {
      url.searchParams.delete('auth_error');
      history.replaceState(null, '', url);
      this.set({ error: AUTH_ERRORS[authError] ?? 'Sign-in failed.' });
    }
    req<Provider[]>('GET', '/auth/providers')
      .then((providers) => this.set({ providers }))
      .catch(() => {});
    try {
      const me = await req<{ user: User; keys: KeyBundle | null }>('GET', '/auth/me');
      await this.signedIn(me.user, me.keys);
    } catch (e) {
      const reachable = !(e instanceof ApiError) || (e.status !== 0 && e.status < 500 && e.status !== 404);
      const stored = await idbGet<StoredIdentity>('kv', 'identity').catch(() => undefined);
      if (!reachable && stored) {
        // Offline with an unlocked device: keep working, sync resumes when the server returns.
        this.set({ status: 'ready', identity: stored, serverReachable: false, user: (await idbGet<User>('kv', 'user').catch(() => undefined)) ?? null });
        return;
      }
      const local = (() => {
        try {
          return localStorage.getItem(LOCAL_FLAG) === '1';
        } catch {
          return false;
        }
      })();
      this.set({ status: local ? 'local' : 'signed_out', serverReachable: reachable });
    }
  }

  private async signedIn(user: User, bundle: KeyBundle | null, identity?: Identity) {
    try {
      localStorage.removeItem(LOCAL_FLAG);
    } catch {
      /* storage blocked */
    }
    const prev = await idbGet<User>('kv', 'user').catch(() => undefined);
    if (prev && prev.id !== user.id) {
      await idbClear().catch(() => {});
      this.onAccountSwitch?.();
    }
    await idbPut('kv', user, 'user').catch(() => {});
    if (!bundle) return this.set({ status: 'needs_keys', user, bundle: null, error: null });
    if (!identity) {
      const stored = await idbGet<StoredIdentity>('kv', 'identity').catch(() => undefined);
      if (stored?.userId === user.id) identity = stored;
    }
    if (!identity) return this.set({ status: 'locked', user, bundle, error: null });
    await idbPut('kv', { ...identity, userId: user.id }, 'identity').catch(() => {});
    this.set({ status: 'ready', user, bundle, identity, error: null, serverReachable: true });
  }

  private async guard(fn: () => Promise<void>) {
    this.set({ error: null });
    try {
      await fn();
    } catch (e) {
      this.set({ error: e instanceof Error ? e.message : 'Something went wrong' });
      throw e;
    }
  }

  signup = (email: string, name: string, password: string) =>
    this.guard(async () => {
      const { bundle, authHash, identity } = await createKeys(password);
      const r = await req<{ user: User; keys: KeyBundle }>('POST', '/auth/signup', { email, name, auth_hash: authHash, keys: bundle });
      await this.signedIn(r.user, r.keys, identity);
    });

  login = (email: string, password: string) =>
    this.guard(async () => {
      const pre = await req<{ kdf_salt: string; kdf_iterations: number }>('POST', '/auth/prelogin', { email });
      const { authHash, vaultKey } = await derive(password, unb64(pre.kdf_salt), pre.kdf_iterations);
      const r = await req<{ user: User; keys: KeyBundle | null }>('POST', '/auth/login', { email, auth_hash: authHash });
      await this.signedIn(r.user, r.keys, r.keys ? await unlockKeys(r.keys, vaultKey) : undefined);
    });

  /** Unlock this device with the account password (or OAuth encryption passphrase). */
  unlock = (passphrase: string) =>
    this.guard(async () => {
      const { user, bundle } = this.state;
      if (!user || !bundle) throw new Error('Not signed in');
      const { vaultKey } = await derive(passphrase, unb64(bundle.kdf_salt), bundle.kdf_iterations);
      let identity: Identity;
      try {
        identity = await unlockKeys(bundle, vaultKey);
      } catch {
        throw new Error('That passphrase does not unlock this account.');
      }
      await this.signedIn(user, bundle, identity);
    });

  /** First-time encryption setup for OAuth accounts. */
  setupKeys = (passphrase: string) =>
    this.guard(async () => {
      const { user } = this.state;
      if (!user) throw new Error('Not signed in');
      const { bundle, identity } = await createKeys(passphrase);
      const r = await req<{ keys: KeyBundle }>('PUT', '/auth/keys', bundle);
      await this.signedIn(user, r.keys, identity);
    });

  continueLocal = () => {
    try {
      localStorage.setItem(LOCAL_FLAG, '1');
    } catch {
      /* storage blocked: local mode lasts for this tab only */
    }
    this.set({ status: 'local', error: null });
  };

  showLogin = () => this.set({ status: 'signed_out', error: null });

  /** Signs out and wipes synced data from this device. */
  logout = async () => {
    await req('POST', '/auth/logout').catch(() => {});
    await idbClear().catch(() => {});
    this.set({ status: 'signed_out', user: null, bundle: null, identity: null, error: null });
  };
}

export const session = new Session();

export function useSession<T>(select: (s: SessionState) => T): T {
  return useSyncExternalStore(session.subscribe, () => select(session.get()));
}
