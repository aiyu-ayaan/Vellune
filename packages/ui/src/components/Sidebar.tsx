import React from 'react';
import {
  Calendar,
  ChevronRight,
  Compass,
  Database,
  FileText,
  Hash,
  Moon,
  Monitor,
  Plus,
  Search,
  Star,
  Sun,
  Trash2,
} from 'lucide-react';
import { store, useStore } from '../store';
import { getTheme, setTheme, Theme } from '../theme';
import { useSession } from '../cloud/session';
import { Account } from './Account';
import { Avatar } from './ShareMenu';

const THEME_ICON = { light: Sun, dark: Moon, system: Monitor } as const;
const NEXT_THEME: Record<Theme, Theme> = { light: 'dark', dark: 'system', system: 'light' };

const Section: React.FC<{ label: string; children: React.ReactNode }> = ({ label, children }) => {
  const [open, setOpen] = React.useState(true);
  return (
    <div className="side-section">
      <button type="button" className="section-label" aria-expanded={open} onClick={() => setOpen((o) => !o)}>
        <ChevronRight size={12} className={open ? 'caret open' : 'caret'} aria-hidden="true" />
        <span>{label}</span>
      </button>
      {open && children}
    </div>
  );
};

export const Sidebar: React.FC<{ onOpenSearch: () => void; onOpenAdmin: () => void }> = ({ onOpenSearch, onOpenAdmin }) => {
  const { pages, activeId, view, databaseTag } = useStore((s) => s);
  const [theme, setThemeState] = React.useState<Theme>(getTheme());

  const activePages = Object.values(pages).filter((p) => !p.deletedAt);
  const favoritePages = activePages.filter((p) => p.favorite);
  const regularPages = activePages.filter((p) => !p.favorite);
  const trashedCount = Object.values(pages).filter((p) => p.deletedAt).length;

  // Extract all unique tags
  const tags = Array.from(new Set(activePages.flatMap((p) => p.tags))).sort();

  const userName = useSession((st) => st.user?.name);
  const docActive = (id: string) => activeId === id && view !== 'graph' && view !== 'database' && view !== 'trash';
  const docRow = (p: (typeof activePages)[number]) => (
    <button
      type="button"
      key={p.id}
      className={`page-list-item ${docActive(p.id) ? 'active' : ''}`}
      onClick={() => store.open(p.id)}
    >
      <FileText size={16} aria-hidden="true" />
      <span className="page-title-text">{p.title || 'Untitled'}</span>
    </button>
  );
  const ThemeIcon = THEME_ICON[theme];

  return (
    <aside className="sidebar">
      <div className="sidebar-header">
        <div className="brand">
          <span className="ws-avatar" aria-hidden="true">V</span>
          <span>Vellune</span>
        </div>
        {userName && <Avatar name={userName} size={22} />}
      </div>

      <div className="sidebar-nav">
        <div className="search-row">
          <button type="button" className="nav-item" onClick={onOpenSearch} title="Quick switcher (Ctrl+K)">
            <Search size={16} aria-hidden="true" />
            <span>Search</span>
          </button>
          <button
            type="button"
            className="btn-icon"
            title="New doc"
            aria-label="New doc"
            onClick={() => store.createPage('Untitled')}
          >
            <Plus size={16} />
          </button>
        </div>
        <button
          type="button"
          className={`nav-item ${view === 'page' && pages[activeId ?? '']?.journal ? 'active' : ''}`}
          onClick={() => store.openToday()}
        >
          <Calendar size={16} aria-hidden="true" />
          <span>Journals</span>
        </button>
        <button type="button" className={`nav-item ${view === 'graph' ? 'active' : ''}`} onClick={() => store.setView('graph')}>
          <Compass size={16} aria-hidden="true" />
          <span>Brain View</span>
        </button>
        <button
          type="button"
          className={`nav-item ${view === 'database' && !databaseTag ? 'active' : ''}`}
          onClick={() => store.setDatabaseTag(null)}
        >
          <Database size={16} aria-hidden="true" />
          <span>Databases</span>
        </button>
      </div>

      <div className="sidebar-pages">
        <Section label="Favorites">
          {favoritePages.length > 0 ? (
            favoritePages.map(docRow)
          ) : (
            <div className="side-empty">
              <span className="side-empty-icon"><Star size={12} aria-hidden="true" /></span>
              No favorites
            </div>
          )}
        </Section>

        <Section label="Docs">{regularPages.map(docRow)}</Section>

        {tags.length > 0 && (
          <Section label="Tags">
            {tags.map((tag) => (
              <button
                type="button"
                key={tag}
                className={`page-list-item ${view === 'database' && databaseTag === tag ? 'active' : ''}`}
                onClick={() => store.setDatabaseTag(tag)}
              >
                <Hash size={16} aria-hidden="true" />
                <span className="page-title-text">{tag}</span>
              </button>
            ))}
          </Section>
        )}

        {trashedCount > 0 && (
          <Section label="Others">
            <button type="button" className={`page-list-item ${view === 'trash' ? 'active' : ''}`} onClick={() => store.setView('trash')}>
              <Trash2 size={16} aria-hidden="true" />
              <span className="page-title-text">Trash ({trashedCount})</span>
            </button>
          </Section>
        )}
      </div>

      <div className="sidebar-bottom">
        <Account onOpenAdmin={onOpenAdmin} />
        <button
          type="button"
          className="btn-icon"
          title={`Theme: ${theme} (click for ${NEXT_THEME[theme]})`}
          aria-label={`Theme: ${theme}. Switch to ${NEXT_THEME[theme]}`}
          onClick={() => {
            const n = NEXT_THEME[theme];
            setTheme(n);
            setThemeState(n);
          }}
        >
          <ThemeIcon size={16} />
        </button>
      </div>
    </aside>
  );
};
