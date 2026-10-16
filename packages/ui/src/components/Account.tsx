import React, { useEffect, useRef, useState } from 'react';
import { CloudOff, LogIn, LogOut, Shield } from 'lucide-react';
import { session, useSession } from '../cloud/session';
import { useSync } from '../cloud/sync';
import { signOut } from '../store';
import { Avatar } from './ShareMenu';
import './cloud.css';

/** Sidebar account row: who is signed in, admin link, sign out. */
export const Account: React.FC<{ onOpenAdmin: () => void }> = ({ onOpenAdmin }) => {
  const status = useSession((s) => s.status);
  const user = useSession((s) => s.user);
  const pending = useSync((s) => s.pending);
  const [open, setOpen] = useState(false);
  const [confirm, setConfirm] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent) => ref.current && !ref.current.contains(e.target as Node) && setOpen(false);
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && setOpen(false);
    document.addEventListener('mousedown', onDown);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onDown);
      document.removeEventListener('keydown', onKey);
    };
  }, [open]);

  if (status !== 'ready' || !user) {
    return (
      <div className="account">
        <button type="button" className="account-btn" onClick={session.showLogin}>
          {status === 'local' ? <CloudOff size={16} aria-hidden="true" /> : <LogIn size={16} aria-hidden="true" />}
          <span>{status === 'local' ? 'Local only · Sign in to sync' : 'Sign in'}</span>
        </button>
      </div>
    );
  }

  return (
    <div className="account" ref={ref}>
      <button type="button" className="account-btn" aria-haspopup="menu" aria-expanded={open} onClick={() => { setOpen((o) => !o); setConfirm(false); }}>
        <Avatar name={user.name || user.email} size={20} />
        <span>{user.name}</span>
      </button>
      {open && (
        <div className="account-menu" role="menu">
          <div className="meta">{user.email}</div>
          {user.is_admin && (
            <button type="button" role="menuitem" onClick={() => { setOpen(false); onOpenAdmin(); }}>
              <Shield size={15} aria-hidden="true" /> Admin panel
            </button>
          )}
          {confirm ? (
            <button type="button" role="menuitem" className="danger" onClick={() => void signOut()}>
              <LogOut size={15} aria-hidden="true" /> {pending ? `Discard ${pending} unsynced change${pending > 1 ? 's' : ''} and sign out` : 'Confirm sign out'}
            </button>
          ) : (
            <button type="button" role="menuitem" className="danger" onClick={() => setConfirm(true)}>
              <LogOut size={15} aria-hidden="true" /> Sign out
            </button>
          )}
        </div>
      )}
    </div>
  );
};
