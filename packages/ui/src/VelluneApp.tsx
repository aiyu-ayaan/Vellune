import React, { useEffect, useState } from 'react';
import { Sidebar } from './components/Sidebar';
import { TopBar } from './components/TopBar';
import { BlockEditor } from './components/BlockEditor';
import { EdgelessCanvas } from './components/EdgelessCanvas';
import { BrainGraph } from './components/BrainGraph';
import { DatabaseView } from './components/DatabaseView';
import { RightSidebar } from './components/RightSidebar';
import { CommandPalette } from './components/CommandPalette';
import { store, useStore } from './store';
import { Trash2, Undo } from 'lucide-react';
import './styles.css';

export const VelluneApp: React.FC = () => {
  const { pages, activeId, view, loaded } = useStore((s) => s);
  const [showInspector, setShowInspector] = useState(true);
  const [showCommandPalette, setShowCommandPalette] = useState(false);

  useEffect(() => {
    store.load();
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

  if (!loaded) {
    return (
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', height: '100vh', background: 'var(--bg-primary)', color: 'var(--text-secondary)' }}>
        Loading Vellune workspace...
      </div>
    );
  }

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
            <div style={{ flex: 1, padding: '2.5rem 3rem', overflowY: 'auto' }}>
              <h2 style={{ fontSize: '1.5rem', fontWeight: 700, marginBottom: '1.5rem', display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
                <Trash2 size={22} color="var(--danger)" />
                <span>Trash</span>
              </h2>

              {trashedPages.length === 0 ? (
                <div style={{ color: 'var(--text-muted)' }}>Trash is empty.</div>
              ) : (
                <div style={{ display: 'flex', flexDirection: 'column', gap: '0.75rem', maxWidth: 640 }}>
                  {trashedPages.map((p) => (
                    <div
                      key={p.id}
                      style={{
                        padding: '1rem',
                        background: 'var(--bg-secondary)',
                        borderRadius: '8px',
                        border: '1px solid var(--border-color)',
                        display: 'flex',
                        justifyContent: 'space-between',
                        alignItems: 'center',
                      }}
                    >
                      <div>
                        <div style={{ fontWeight: 600 }}>{p.title || 'Untitled'}</div>
                        <div style={{ fontSize: '0.78rem', color: 'var(--text-muted)' }}>
                          Deleted {p.deletedAt ? new Date(p.deletedAt).toLocaleDateString() : ''}
                        </div>
                      </div>

                      <div style={{ display: 'flex', gap: '0.5rem' }}>
                        <button
                          className="btn-icon"
                          title="Restore Note"
                          onClick={() => store.restore(p.id)}
                          style={{ padding: '0.35rem 0.6rem', display: 'flex', gap: '4px', fontSize: '0.8rem' }}
                        >
                          <Undo size={14} />
                          <span>Restore</span>
                        </button>
                        <button
                          className="btn-icon"
                          title="Permanently Delete"
                          onClick={() => store.purge(p.id)}
                          style={{ color: 'var(--danger)', padding: '0.35rem 0.6rem', display: 'flex', gap: '4px', fontSize: '0.8rem' }}
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

      {/* Quick Switcher / Command Palette Modal */}
      <CommandPalette
        isOpen={showCommandPalette}
        onClose={() => setShowCommandPalette(false)}
      />
    </div>
  );
};
