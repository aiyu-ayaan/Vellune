import React, { useRef } from 'react';
import {
  Download,
  FileText,
  Layout,
  PanelRight,
  Star,
  Trash2,
  Upload,
} from 'lucide-react';
import { store, useStore } from '../store';
import { markdownToPage, pageToMarkdown } from '../markdown';

export const TopBar: React.FC<{
  showInspector: boolean;
  onToggleInspector: () => void;
}> = ({ showInspector, onToggleInspector }) => {
  const { pages, activeId, view } = useStore((s) => s);
  const activePage = activeId ? pages[activeId] : null;
  const fileInputRef = useRef<HTMLInputElement>(null);

  const handleExport = () => {
    if (!activePage) return;
    const md = pageToMarkdown(activePage);
    const blob = new Blob([md], { type: 'text/markdown;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `${(activePage.title || 'untitled').replace(/[^a-z0-9_-]/gi, '_')}.md`;
    a.click();
    URL.revokeObjectURL(url);
  };

  const handleImportFile = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    const reader = new FileReader();
    reader.onload = (event) => {
      const content = event.target?.result as string;
      if (content) {
        const title = file.name.replace(/\.md$/i, '');
        const imported = markdownToPage(title, content);
        store.addPages([imported]);
        store.open(imported.id, 'page');
      }
    };
    reader.readAsText(file);
    if (fileInputRef.current) fileInputRef.current.value = '';
  };

  return (
    <header className="topbar">
      <input
        type="file"
        ref={fileInputRef}
        onChange={handleImportFile}
        accept=".md,.markdown,.txt"
        style={{ display: 'none' }}
      />

      <div style={{ display: 'flex', alignItems: 'center', gap: '0.75rem' }}>
        {activePage && view !== 'graph' && view !== 'database' && view !== 'trash' && (
          <div className="view-tabs">
            <button
              className={`view-tab ${view === 'page' ? 'active' : ''}`}
              onClick={() => store.setView('page')}
              title="Document View (AFFiNE / Obsidian doc)"
            >
              <FileText size={15} />
              <span>Page</span>
            </button>
            <button
              className={`view-tab ${view === 'edgeless' ? 'active' : ''}`}
              onClick={() => store.setView('edgeless')}
              title="Edgeless Infinite Canvas (AFFiNE whiteboard)"
            >
              <Layout size={15} />
              <span>Edgeless</span>
            </button>
          </div>
        )}

        {(view === 'graph' || view === 'database' || view === 'trash') && (
          <span style={{ fontWeight: 600, fontSize: '0.95rem' }}>
            {view === 'graph' && 'Knowledge Graph (Brain View)'}
            {view === 'database' && 'Database View'}
            {view === 'trash' && 'Trash'}
          </span>
        )}
      </div>

      <div className="topbar-actions">
        {activePage && !activePage.deletedAt && (
          <>
            <button
              className="btn-icon"
              title={activePage.favorite ? 'Remove from favorites' : 'Add to favorites'}
              onClick={() => store.updatePage(activePage.id, (p) => ({ ...p, favorite: !p.favorite }))}
            >
              <Star
                size={16}
                fill={activePage.favorite ? 'var(--accent)' : 'none'}
                color={activePage.favorite ? 'var(--accent)' : 'var(--text-secondary)'}
              />
            </button>
            <button className="btn-icon" title="Export Markdown" onClick={handleExport}>
              <Download size={16} />
            </button>
            <button
              className="btn-icon"
              title="Import Markdown file"
              onClick={() => fileInputRef.current?.click()}
            >
              <Upload size={16} />
            </button>
            <button
              className="btn-icon"
              title="Delete Note"
              onClick={() => store.trash(activePage.id)}
            >
              <Trash2 size={16} />
            </button>
          </>
        )}

        <button
          className={`btn-icon ${showInspector ? 'active' : ''}`}
          title="Toggle Backlinks & Inspector Panel"
          onClick={onToggleInspector}
        >
          <PanelRight size={16} />
        </button>
      </div>
    </header>
  );
};
