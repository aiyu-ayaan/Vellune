import React, { useEffect, useRef, useState } from 'react';
import { Sidebar } from './components/Sidebar';
import { TopBar } from './components/TopBar';
import { BlockEditor } from './components/BlockEditor';
import { EdgelessCanvas } from './components/EdgelessCanvas';
import { BrainGraph } from './components/BrainGraph';
import { DatabaseView } from './components/DatabaseView';
import { RightSidebar } from './components/RightSidebar';
import { CommandPalette } from './components/CommandPalette';
import { store, useStore } from './store';
import { LoginScreen, KeyScreen } from './components/AuthScreen';
import { PublicPage } from './components/PublicPage';
import { AdminApp } from './admin/AdminApp';
import { session, useSession } from './cloud/session';
import { sync } from './cloud/sync';
import { Trash2, Undo } from 'lucide-react';
import './theme';
import './styles.css';
import './components/editor.css';
import { ContextMenuHost, suppressNativeContextMenu } from './components/ContextMenu';
import { Route, parseRoute, routePath } from './route';

const navigate = (path: string) => {
  history.pushState(null, '', path);
  dispatchEvent(new PopStateEvent('popstate'));
};

/** Routes: /share/{id}#k=… (public page), /admin (admin panel), everything else is the workspace. */
export const VelluneApp: React.FC = () => {
  const [path, setPath] = useState(location.pathname);
  useEffect(() => {
    const onPop = () => setPath(location.pathname);
    addEventListener('popstate', onPop);
    return () => removeEventListener('popstate', onPop);
  }, []);

  const share = path.match(/^\/share\/([0-9a-f-]{36})\/?$/i);
  if (share) return <PublicPage pageId={share[1]} rawKey={new URLSearchParams(location.hash.slice(1)).get('k') ?? ''} />;
  if (path.startsWith('/admin')) return <AdminRoute />;
  return <Workspace />;
};

const Centered: React.FC<{ children: React.ReactNode }> = ({ children }) => (
  <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', height: '100vh', background: 'var(--bg-primary)', color: 'var(--text-secondary)' }}>
    {children}
  </div>
);

/** /admin: same account and password as the app; signed-out visitors get the normal sign-in screen. */
const AdminRoute: React.FC = () => {
  const status = useSession((s) => s.status);
  useEffect(() => { void session.init(); }, []);
  if (status === 'signed_out') return <LoginScreen />;
  return <AdminApp key={status === 'loading' ? 'loading' : 'in'} onExit={() => navigate('/')} />;
};

const Workspace: React.FC = () => {
  const status = useSession((s) => s.status);
  const identity = useSession((s) => s.identity);
  const { pages, activeId, view, loaded, databaseTag } = useStore((s) => s);
  const [showInspector, setShowInspector] = useState(false);
  const [showCommandPalette, setShowCommandPalette] = useState(false);

  useEffect(() => {
    store.load();
    void session.init();
  }, []);

  // Sync runs only while signed in and unlocked; editing never waits for it.
  useEffect(() => {
    if (loaded && status === 'ready' && identity) void sync.start(identity);
    else sync.stop();
  }, [loaded, status, identity]);

  // URL <-> store. A deep link to a page this device has not synced yet waits for it (10 s max).
  const wanted = useRef<Route | null>(parseRoute(location.pathname, location.search));
  const routed = useRef(false);
  const lastActive = useRef<string | null>(null);
  useEffect(() => {
    const apply = () => {
      const r = wanted.current;
      if (!r) return;
      const s = store.get();
      if (r.view === 'database') store.setDatabaseTag(r.tag ?? null);
      else if (!r.pageId) store.setView(r.view);
      else if (s.pages[r.pageId] && !s.pages[r.pageId].folder) store.open(r.pageId, r.view);
      else return;
      wanted.current = null;
      // The address bar already shows this route; later navigation pushes new history entries.
      routed.current = true;
      lastActive.current = store.get().activeId;
    };
    if (loaded) apply();
    const onPop = () => {
      const r = parseRoute(location.pathname, location.search);
      // Back/forward to a page that no longer exists: stay put instead of waiting for it.
      if (r?.pageId && !store.get().pages[r.pageId]) {
        const s = store.get();
        history.replaceState(null, '', routePath({ view: s.view, pageId: s.activeId ?? undefined, tag: s.databaseTag }));
        return;
      }
      wanted.current = r;
      apply();
    };
    addEventListener('popstate', onPop);
    return () => removeEventListener('popstate', onPop);
  }, [loaded, pages]);
  useEffect(() => {
    if (!loaded || !wanted.current) return;
    const t = setTimeout(() => (wanted.current = null), 10_000);
    return () => clearTimeout(t);
  }, [loaded]);
  useEffect(() => {
    if (!loaded || wanted.current) return;
    const path = routePath({ view, pageId: activeId ?? undefined, tag: databaseTag });
    if (path === location.pathname + location.search) return;
    // First sync replaces "/" (no extra history entry); later navigation is a real history step,
    // except when the app moved off a page that disappeared (trashed, revoked, dropped starter page).
    const prev = lastActive.current;
    const automatic = !routed.current || (prev !== null && prev !== activeId && (!pages[prev] || !!pages[prev].deletedAt));
    if (automatic) history.replaceState(null, '', path);
    else history.pushState(null, '', path);
    routed.current = true;
    lastActive.current = activeId;
  }, [loaded, view, activeId, databaseTag, pages]);

  // The app has its own right-click menus; the browser's is kept only in text fields.
  useEffect(() => {
    document.addEventListener('contextmenu', suppressNativeContextMenu);
    return () => document.removeEventListener('contextmenu', suppressNativeContextMenu);
  }, []);

  // Global keyboard shortcuts (Cmd/Ctrl + K for search)
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key === 'k') {
        e.preventDefault();
        setShowCommandPalette((open) => !open);
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, []);

  const activePage = activeId ? pages[activeId] : null;
  const trashedPages = Object.values(pages).filter((p) => p.deletedAt);

  if (!loaded || status === 'loading') return <Centered>Loading Vellune workspace…</Centered>;
  if (status === 'signed_out') return <LoginScreen />;
  if (status === 'needs_keys') return <KeyScreen mode="setup" />;
  if (status === 'locked') return <KeyScreen mode="unlock" />;

  return (
    <div className="vellune-container">
      {/* Navigation Sidebar */}
      <Sidebar onOpenSearch={() => setShowCommandPalette(true)} />

      {/* Main Content Area */}
      <main className="main-content">
        <TopBar
          showInspector={showInspector}
          onToggleInspector={() => setShowInspector((v) => !v)}
        />

        <div className="workspace-body">
          {view === 'page' && activePage && !activePage.deletedAt && (
            <BlockEditor page={activePage} />
          )}

          {view === 'edgeless' && activePage && !activePage.deletedAt && (
            <EdgelessCanvas page={activePage} />
          )}

          {view === 'graph' && <BrainGraph />}

          {view === 'database' && <DatabaseView />}

          {view === 'trash' && (
            <div style={{ flex: 1, padding: '48px 96px', overflowY: 'auto' }}>
              <h2 style={{ fontSize: '30px', fontWeight: 700, marginBottom: '16px', display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
                <Trash2 size={22} color="var(--danger)" />
                <span>Trash</span>
              </h2>

              {trashedPages.length === 0 ? (
                <div style={{ color: 'var(--text-muted)' }}>Trash is empty.</div>
              ) : (
                <div style={{ display: 'flex', flexDirection: 'column', gap: '2px', maxWidth: 708 }}>
                  {trashedPages.map((p) => (
                    <div
                      key={p.id}
                      className="trash-row"
                    >
                      <div>
                        <div style={{ fontWeight: 500 }}>{p.title || 'Untitled'}</div>
                        <div style={{ fontSize: '12px', color: 'var(--text-muted)' }}>
                          Deleted {p.deletedAt ? new Date(p.deletedAt).toLocaleDateString() : ''}
                        </div>
                      </div>

                      <div style={{ display: 'flex', gap: '0.5rem' }}>
                        <button
                          className="btn-icon"
                          title="Restore Note"
                          onClick={() => store.restore(p.id)}
                          style={{ padding: '4px 8px', display: 'flex', gap: '4px', fontSize: '13px' }}
                        >
                          <Undo size={14} />
                          <span>Restore</span>
                        </button>
                        <button
                          className="btn-icon"
                          title="Permanently Delete"
                          onClick={() => store.purge(p.id)}
                          style={{ color: 'var(--danger)', padding: '4px 8px', display: 'flex', gap: '4px', fontSize: '13px' }}
                        >
                          <Trash2 size={14} />
                          <span>Purge</span>
                        </button>
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </div>
          )}

          {(!activePage || activePage.deletedAt) && view === 'page' && (
            <div style={{ flex: 1, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', color: 'var(--text-muted)' }}>
              <p>No active note selected.</p>
              <button
                onClick={() => store.createPage('Untitled')}
                style={{
                  background: 'var(--accent)',
                  color: 'white',
                  border: 'none',
                  borderRadius: '6px',
                  padding: '0.5rem 1rem',
                  cursor: 'pointer',
                  fontWeight: 500,
                }}
              >
                Create a Note
              </button>
            </div>
          )}

          {/* Right Inspector Panel */}
          {showInspector && activePage && !activePage.deletedAt && (view === 'page' || view === 'edgeless') && (
            <RightSidebar page={activePage} />
          )}
        </div>
      </main>

      <ContextMenuHost />

      {/* Quick Switcher / Command Palette Modal */}
      <CommandPalette
        isOpen={showCommandPalette}
        onClose={() => setShowCommandPalette(false)}
      />
    </div>
  );
};
