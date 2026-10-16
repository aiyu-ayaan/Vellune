import React from 'react';
import {
  BookOpen,
  Calendar,
  Compass,
  Database,
  FilePlus,
  Hash,
  Moon,
  Search,
  Star,
  Trash2,
} from 'lucide-react';
import { store, useStore } from '../store';
import { getTheme, setTheme, Theme } from '../theme';
import { Account } from './Account';

export const Sidebar: React.FC<{ onOpenSearch: () => void; onOpenAdmin: () => void }> = ({ onOpenSearch, onOpenAdmin }) => {
  const { pages, activeId, view, databaseTag } = useStore((s) => s);
  const [theme, setThemeState] = React.useState<Theme>(getTheme());

  const activePages = Object.values(pages).filter((p) => !p.deletedAt);
  const favoritePages = activePages.filter((p) => p.favorite);
  const regularPages = activePages.filter((p) => !p.favorite);
  const trashedCount = Object.values(pages).filter((p) => p.deletedAt).length;

  // Extract all unique tags
  const tags = Array.from(new Set(activePages.flatMap((p) => p.tags))).sort();

  return (
    <aside className="sidebar">
      <div className="sidebar-header">
        <div className="brand">
          <Moon className="brand-icon" size={18} aria-hidden="true" />
          <span>Vellune</span>
        </div>
        <button
          className="btn-icon"
          title="New Note"
          aria-label="New note"
          onClick={() => store.createPage('Untitled')}
        >
          <FilePlus size={16} />
        </button>
      </div>

      <div className="sidebar-nav">
        <button className="nav-item" onClick={onOpenSearch}>
          <Search size={16} />
          <span>Quick Switcher</span>
          <span style={{ marginLeft: 'auto', fontSize: '12px', color: 'var(--text-muted)' }}>⌘K</span>
        </button>
        <button
          className={`nav-item ${view === 'page' && pages[activeId ?? '']?.journal ? 'active' : ''}`}
          onClick={() => store.openToday()}
        >
          <Calendar size={16} />
          <span>Daily Journal</span>
        </button>
        <button
          className={`nav-item ${view === 'graph' ? 'active' : ''}`}
          onClick={() => store.setView('graph')}
        >
          <Compass size={16} />
          <span>Brain View (Graph)</span>
        </button>
        <button
          className={`nav-item ${view === 'database' && !databaseTag ? 'active' : ''}`}
          onClick={() => store.setDatabaseTag(null)}
        >
          <Database size={16} />
          <span>Databases</span>
        </button>
      </div>

      <div className="sidebar-pages">
        {favoritePages.length > 0 && (
          <div style={{ marginBottom: '1rem' }}>
            <div className="section-label">
              <span>Favorites</span>
              <Star size={12} />
            </div>
            {favoritePages.map((p) => (
              <div
                key={p.id}
                className={`page-list-item ${activeId === p.id && view !== 'graph' && view !== 'database' && view !== 'trash' ? 'active' : ''}`}
                onClick={() => store.open(p.id)}
              >
                <span className="page-title-text">{p.title || 'Untitled'}</span>
              </div>
            ))}
          </div>
        )}

        <div style={{ marginBottom: '1rem' }}>
          <div className="section-label">
            <span>Notes</span>
            <BookOpen size={12} />
          </div>
          {regularPages.map((p) => (
            <div
              key={p.id}
              className={`page-list-item ${activeId === p.id && view !== 'graph' && view !== 'database' && view !== 'trash' ? 'active' : ''}`}
              onClick={() => store.open(p.id)}
            >
              <span className="page-title-text">{p.title || 'Untitled'}</span>
            </div>
          ))}
        </div>

        {tags.length > 0 && (
          <div style={{ marginBottom: '1rem' }}>
            <div className="section-label">
              <span>Tags</span>
              <Hash size={12} />
            </div>
            {tags.map((tag) => (
              <div
                key={tag}
                className={`page-list-item ${view === 'database' && databaseTag === tag ? 'active' : ''}`}
                onClick={() => store.setDatabaseTag(tag)}
              >
                <span className="page-title-text">#{tag}</span>
              </div>
            ))}
          </div>
        )}

        {trashedCount > 0 && (
          <div>
            <div
              className={`page-list-item ${view === 'trash' ? 'active' : ''}`}
              onClick={() => store.setView('trash')}
              style={{ color: 'var(--text-muted)' }}
            >
              <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
                <Trash2 size={14} />
                <span>Trash ({trashedCount})</span>
              </div>
            </div>
          </div>
        )}
      </div>

      <Account onOpenAdmin={onOpenAdmin} />
      <div className="sidebar-footer">
        <div className="theme-toggle" role="group" aria-label="Theme">
          {(['light', 'dark', 'system'] as Theme[]).map((t) => (
            <button
              key={t}
              type="button"
              aria-pressed={theme === t}
              onClick={() => {
                setTheme(t);
                setThemeState(t);
              }}
            >
              {t === 'light' ? 'Light' : t === 'dark' ? 'Dark' : 'System'}
            </button>
          ))}
        </div>
        <span>v0.1.0</span>
      </div>
    </aside>
  );
};
