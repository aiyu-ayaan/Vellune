import React, { useEffect, useState } from 'react';
import { BookOpen, Calendar, Compass, Database, FilePlus, Layout, Search } from 'lucide-react';
import { store, useStore } from '../store';

export const CommandPalette: React.FC<{ isOpen: boolean; onClose: () => void }> = ({
  isOpen,
  onClose,
}) => {
  const { pages, activeId } = useStore((s) => s);
  const [query, setQuery] = useState('');
  const [selectedIndex, setSelectedIndex] = useState(0);

  const activePages = Object.values(pages).filter((p) => !p.deletedAt);

  // Filter notes
  const matchedPages = activePages.filter((p) => {
    const q = query.toLowerCase();
    return (
      p.title.toLowerCase().includes(q) ||
      p.tags.some((t) => t.toLowerCase().includes(q)) ||
      (p.aliases && p.aliases.some((a) => a.toLowerCase().includes(q)))
    );
  });

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
      <div className="modal-dialog" onClick={(e) => e.stopPropagation()}>
        <div style={{ display: 'flex', alignItems: 'center', padding: '0 1rem' }}>
          <Search size={18} color="var(--text-muted)" />
          <input
            className="modal-input"
            placeholder="Search notes, commands, or type to create..."
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            onKeyDown={handleKeyDown}
            autoFocus
          />
        </div>

        <div className="modal-list">
          {matchedPages.map((p, idx) => (
            <div
              key={p.id}
              className={`modal-item ${selectedIndex === idx ? 'active' : ''}`}
              onClick={() => {
                store.open(p.id, 'page');
                onClose();
              }}
            >
              <BookOpen size={16} />
              <span style={{ fontWeight: 500 }}>{p.title || 'Untitled'}</span>
              {p.tags.length > 0 && (
                <span style={{ fontSize: '0.75rem', color: 'var(--text-muted)', marginLeft: 'auto' }}>
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
