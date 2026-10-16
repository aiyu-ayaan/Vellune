import React, { useEffect, useMemo, useState } from 'react';
import { searchPages } from '../search';
import { Archive, BookOpen, Calendar, Compass, Database, FilePlus, Layout, Search } from 'lucide-react';
import { download, exportWorkspace } from '../vault';
import { store, useStore } from '../store';
import { PageIcon } from './PageIcon';

export const CommandPalette: React.FC<{ isOpen: boolean; onClose: () => void }> = ({
  isOpen,
  onClose,
}) => {
  const { pages, activeId } = useStore((s) => s);
  const [query, setQuery] = useState('');
  const [selectedIndex, setSelectedIndex] = useState(0);

  const hits = useMemo(() => searchPages(Object.values(pages), query), [pages, query]);
  const matchedPages = hits.map((h) => h.page);

  const actions = [
    {
      id: 'new-note',
      title: query ? `Create note "${query}"` : 'Create new note',
      icon: <FilePlus size={16} />,
      run: () => {
        store.createPage(query || 'Untitled');
        onClose();
      },
    },
    {
      id: 'daily',
      title: 'Open Today\'s Daily Journal',
      icon: <Calendar size={16} />,
      run: () => {
        store.openToday();
        onClose();
      },
    },
    {
      id: 'export-workspace',
      title: 'Export workspace as Markdown (.zip)',
      icon: <Archive size={16} />,
      run: () => {
        download(exportWorkspace(Object.values(pages)), `vellune-${new Date().toISOString().slice(0, 10)}.zip`);
        onClose();
      },
    },
    {
      id: 'brain-view',
      title: 'Switch to Brain View (Graph)',
      icon: <Compass size={16} />,
      run: () => {
        store.setView('graph');
        onClose();
      },
    },
    {
      id: 'canvas-view',
      title: 'Switch to Edgeless Canvas',
      icon: <Layout size={16} />,
      run: () => {
        if (activeId) store.open(activeId, 'edgeless');
        onClose();
      },
    },
    {
      id: 'db-view',
      title: 'Switch to Database View',
      icon: <Database size={16} />,
      run: () => {
        store.setDatabaseTag(null);
        onClose();
      },
    },
  ];

  const totalItems = matchedPages.length + actions.length;

  useEffect(() => {
    setSelectedIndex(0);
  }, [query]);

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Escape') {
      onClose();
    } else if (e.key === 'ArrowDown') {
      e.preventDefault();
      setSelectedIndex((idx) => (idx + 1) % totalItems);
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      setSelectedIndex((idx) => (idx - 1 + totalItems) % totalItems);
    } else if (e.key === 'Enter') {
      e.preventDefault();
      if (selectedIndex < matchedPages.length) {
        store.open(matchedPages[selectedIndex].id, 'page');
        onClose();
      } else {
        const actionIndex = selectedIndex - matchedPages.length;
        actions[actionIndex]?.run();
      }
    }
  };

  if (!isOpen) return null;

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-dialog" role="dialog" aria-modal="true" aria-label="Quick switcher" onClick={(e) => e.stopPropagation()}>
        <div className="modal-header">
          <Search size={18} color="var(--text-muted)" aria-hidden="true" />
          <input
            className="modal-input"
            aria-label="Search notes and commands"
            placeholder="Search notes, commands, or type to create..."
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            onKeyDown={handleKeyDown}
            autoFocus
          />
        </div>

        <div className="modal-list">
          {!query.trim() && hits.length > 0 && <div className="popover-label">Recent</div>}
          {hits.map(({ page: p, snippet }, idx) => (
            <div
              key={p.id}
              className={`modal-item ${selectedIndex === idx ? 'active' : ''}`}
              onClick={() => {
                store.open(p.id, 'page');
                onClose();
              }}
            >
              <PageIcon page={p} fallback={BookOpen} />
              <span className="modal-item-text">
                <span>{p.title || 'Untitled'}</span>
                {snippet && (
                  <span className="modal-snippet">
                    {snippet.before}
                    <mark>{snippet.match}</mark>
                    {snippet.after}
                  </span>
                )}
              </span>
              {p.tags.length > 0 && (
                <span style={{ fontSize: '12px', color: 'var(--text-muted)', marginLeft: 'auto' }}>
                  {p.tags.map((t) => `#${t}`).join(' ')}
                </span>
              )}
            </div>
          ))}

          {actions.map((act, idx) => {
            const itemIdx = matchedPages.length + idx;
            return (
              <div
                key={act.id}
                className={`modal-item ${selectedIndex === itemIdx ? 'active' : ''}`}
                onClick={act.run}
              >
                {act.icon}
                <span>{act.title}</span>
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
};
