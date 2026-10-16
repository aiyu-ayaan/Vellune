import React, { useEffect, useRef, useState } from 'react';
import {
  Archive,
  Download,
  FolderInput,
  FileText,
  Layout,
  Lock,
  MoreHorizontal,
  PanelLeft,
  PanelRight,
  Star,
  Trash2,
  Upload,
} from 'lucide-react';
import { store, useStore } from '../store';
import { pageToMarkdown } from '../markdown';
import { download, exportWorkspace, importFiles } from '../vault';
import { useSession } from '../cloud/session';
import { useSync } from '../cloud/sync';
import { Avatar, ShareMenu } from './ShareMenu';
import { VersionHistory } from './VersionHistory';
import { History } from 'lucide-react';

const SyncStatus: React.FC = () => {
  const status = useSession((s) => s.status);
  const connection = useSync((s) => s.connection);
  const pending = useSync((s) => s.pending);
  if (status !== 'ready') return null;
  const label =
    connection === 'denied'
      ? 'Sync paused'
      : connection === 'online' ? (pending ? 'Syncing…' : 'Synced') : connection === 'connecting' ? 'Connecting…' : `Offline${pending ? ` · ${pending} pending` : ''}`;
  return (
    <span
      className="sync-status"
      role="status"
      aria-live="polite"
      title={connection === 'denied' ? 'The server did not accept this session. Sign out and sign in again; your edits stay on this device.' : undefined}
    >
      <span className={`sync-dot ${connection === 'online' && !pending ? 'online' : connection === 'offline' ? '' : connection === 'denied' ? 'denied' : 'connecting'}`} aria-hidden="true" />
      {label}
    </span>
  );
};

const Presence: React.FC<{ pageId: string }> = ({ pageId }) => {
  const me = useSession((s) => s.user?.id);
  const users = useSync((s) => s.presence[pageId]);
  const others = (users ?? []).filter((u) => u.id !== me);
  if (!others.length) return null;
  return (
    <div className="presence" aria-label={`Also viewing: ${others.map((u) => u.name).join(', ')}`}>
      {others.slice(0, 4).map((u) => (
        <Avatar key={u.id} name={u.name} />
      ))}
    </div>
  );
};

export const TopBar: React.FC<{
  /** Set while the sidebar is collapsed: shows a button that brings it back. */
  onOpenSidebar?: () => void;
  showInspector: boolean;
  onToggleInspector: () => void;
}> = ({ onOpenSidebar, showInspector, onToggleInspector }) => {
  const { pages, activeId, view } = useStore((s) => s);
  const activePage = activeId ? pages[activeId] : null;
  const fileInputRef = useRef<HTMLInputElement>(null);
  const folderInputRef = useRef<HTMLInputElement>(null);
  const [shareOpen, setShareOpen] = useState(false);
  const [moreOpen, setMoreOpen] = useState(false);
  const [historyOpen, setHistoryOpen] = useState(false);

  // Ctrl/Cmd+Alt+Shift+H opens version history, as in Google Docs.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.altKey && e.shiftKey && e.code === 'KeyH') {
        e.preventDefault();
        setHistoryOpen(true);
      }
    };
    addEventListener('keydown', onKey);
    return () => removeEventListener('keydown', onKey);
  }, []);
  const moreRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!moreOpen) return;
    const onDown = (e: MouseEvent) => moreRef.current && !moreRef.current.contains(e.target as Node) && setMoreOpen(false);
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && setMoreOpen(false);
    document.addEventListener('mousedown', onDown);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onDown);
      document.removeEventListener('keydown', onKey);
    };
  }, [moreOpen]);

  const handleExport = () => {
    if (!activePage) return;
    const blob = new Blob([pageToMarkdown(activePage)], { type: 'text/markdown;charset=utf-8' });
    download(blob, `${(activePage.title || 'untitled').replace(/[^a-z0-9_-]/gi, '_')}.md`);
  };

  const handleImportFile = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const input = e.currentTarget;
    const imported = await importFiles([...(input.files ?? [])]);
    input.value = '';
    if (!imported.length) return;
    store.addPages(imported);
    const first = imported.find((p) => !p.folder);
    if (first) store.open(first.id, 'page');
  };

  return (
    <header className="topbar">
      <input
        type="file"
        ref={fileInputRef}
        onChange={handleImportFile}
        accept=".md,.markdown,.txt"
        multiple
        style={{ display: 'none' }}
      />
      <input
        type="file"
        ref={folderInputRef}
        onChange={handleImportFile}
        // Non-standard but supported by every engine Vellune runs on (Chromium, WebKit, Gecko).
        {...{ webkitdirectory: '' }}
        style={{ display: 'none' }}
      />

      <div className="topbar-left">
        {onOpenSidebar && (
          <button type="button" className="btn-icon" title="Show sidebar (Ctrl+\\)" aria-label="Show sidebar" onClick={onOpenSidebar}>
            <PanelLeft size={16} aria-hidden="true" />
          </button>
        )}
        {activePage && view !== 'graph' && view !== 'database' && view !== 'trash' && (
          <>
            <div className="view-tabs" role="group" aria-label="Editor mode">
              <button
                type="button"
                className={`view-tab ${view === 'page' ? 'active' : ''}`}
                onClick={() => store.setView('page')}
                title="Page mode"
                aria-label="Page mode"
                aria-pressed={view === 'page'}
              >
                <FileText size={16} />
              </button>
              <button
                type="button"
                className={`view-tab ${view === 'edgeless' ? 'active' : ''}`}
                onClick={() => store.setView('edgeless')}
                title="Edgeless mode"
                aria-label="Edgeless mode"
                aria-pressed={view === 'edgeless'}
              >
                <Layout size={16} />
              </button>
            </div>
            <span className="topbar-title" title={activePage.title || 'Untitled'}>
              {activePage.icon && <span className="topbar-emoji" aria-hidden="true">{activePage.icon}</span>}
              {activePage.title || 'Untitled'}
            </span>
            {!activePage.deletedAt && (
              <button
                type="button"
                className="btn-icon"
                title={activePage.favorite ? 'Remove from favorites' : 'Add to favorites'}
                aria-label={activePage.favorite ? 'Remove from favorites' : 'Add to favorites'}
                aria-pressed={activePage.favorite}
                onClick={() => store.updatePage(activePage.id, (p) => ({ ...p, favorite: !p.favorite }))}
              >
                <Star
                  size={16}
                  fill={activePage.favorite ? 'var(--accent)' : 'none'}
                  color={activePage.favorite ? 'var(--accent)' : 'var(--text-secondary)'}
                />
              </button>
            )}
            {!activePage.deletedAt && (
              <div className="topbar-more" ref={moreRef}>
                <button
                  type="button"
                  className="btn-icon"
                  title="More actions"
                  aria-label="More actions"
                  aria-haspopup="menu"
                  aria-expanded={moreOpen}
                  onClick={() => setMoreOpen((o) => !o)}
                >
                  <MoreHorizontal size={16} />
                </button>
                {historyOpen && <VersionHistory page={activePage} onClose={() => setHistoryOpen(false)} />}
                {moreOpen && (
                  <div className="more-menu" role="menu">
                    <button type="button" role="menuitem" onClick={() => { setMoreOpen(false); setHistoryOpen(true); }}>
                      <History size={15} aria-hidden="true" /> Version history
                    </button>
                    <button type="button" role="menuitem" onClick={() => { setMoreOpen(false); handleExport(); }}>
                      <Download size={15} aria-hidden="true" /> Export page as Markdown
                    </button>
                    <button
                      type="button"
                      role="menuitem"
                      onClick={() => {
                        setMoreOpen(false);
                        download(exportWorkspace(Object.values(store.get().pages)), `vellune-${new Date().toISOString().slice(0, 10)}.zip`);
                      }}
                    >
                      <Archive size={15} aria-hidden="true" /> Export workspace (.zip)
                    </button>
                    <button type="button" role="menuitem" onClick={() => { setMoreOpen(false); fileInputRef.current?.click(); }}>
                      <Upload size={15} aria-hidden="true" /> Import Markdown files
                    </button>
                    <button type="button" role="menuitem" onClick={() => { setMoreOpen(false); folderInputRef.current?.click(); }}>
                      <FolderInput size={15} aria-hidden="true" /> Import folder (Obsidian vault)
                    </button>
                    <button type="button" role="menuitem" className="danger" onClick={() => { setMoreOpen(false); store.trash(activePage.id); }}>
                      <Trash2 size={15} aria-hidden="true" /> Move to trash
                    </button>
                  </div>
                )}
              </div>
            )}
          </>
        )}

        {(view === 'graph' || view === 'database' || view === 'trash') && (
          <span className="topbar-title">
            {view === 'graph' && 'Brain View'}
            {view === 'database' && 'Database View'}
            {view === 'trash' && 'Trash'}
          </span>
        )}
      </div>

      <div className="topbar-actions">
        <SyncStatus />
        {activePage && !activePage.deletedAt && (
          <>
            <Presence pageId={activePage.id} />
            <div className="topbar-share">
              <button type="button" className="btn-primary" aria-haspopup="dialog" aria-expanded={shareOpen} onClick={() => setShareOpen((o) => !o)}>
                <Lock size={13} aria-hidden="true" />
                Share
              </button>
              {shareOpen && <ShareMenu pageId={activePage.id} onClose={() => setShareOpen(false)} />}
            </div>
          </>
        )}

        <button
          className={`btn-icon ${showInspector ? 'active' : ''}`}
          title="Toggle Backlinks & Inspector Panel"
          aria-label="Toggle inspector panel"
          aria-pressed={showInspector}
          onClick={onToggleInspector}
        >
          <PanelRight size={16} />
        </button>
      </div>
    </header>
  );
};
