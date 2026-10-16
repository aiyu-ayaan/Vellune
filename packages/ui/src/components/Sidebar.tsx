import React from 'react';
import {
  Calendar,
  ChevronRight,
  Compass,
  Database,
  Copy,
  ExternalLink,
  FileText,
  FilePlus,
  Folder,
  FolderInput,
  FolderOpen,
  FolderPlus,
  Hash,
  Link2,
  MoreHorizontal,
  Pencil,
  Moon,
  Monitor,
  Plus,
  Search,
  Star,
  StarOff,
  Sun,
  Trash2,
} from 'lucide-react';
import './sidebar.css';
import { openContextMenu, openContextMenuAt, MenuEntry } from './ContextMenu';
import { routePath } from '../route';
import type { Page } from '../model';
import { store, useStore } from '../store';
import { getTheme, setTheme, Theme } from '../theme';
import { useSession } from '../cloud/session';
import { Account } from './Account';
import { PageIcon } from './PageIcon';
import { Avatar } from './ShareMenu';

const THEME_ICON = { light: Sun, dark: Moon, system: Monitor } as const;
const NEXT_THEME: Record<Theme, Theme> = { light: 'dark', dark: 'system', system: 'light' };

const Section: React.FC<{ label: string; children: React.ReactNode; action?: React.ReactNode; headProps?: React.HTMLAttributes<HTMLDivElement> }> = ({ label, children, action, headProps }) => {
  const [open, setOpen] = React.useState(true);
  return (
    <div className="side-section">
      <div className="section-head" {...headProps}>
        <button type="button" className="section-label" aria-expanded={open} onClick={() => setOpen((o) => !o)}>
          <ChevronRight size={12} className={open ? 'caret open' : 'caret'} aria-hidden="true" />
          <span>{label}</span>
        </button>
        {action}
      </div>
      {open && children}
    </div>
  );
};

// ---------- Organize tree ----------

const EXP_KEY = 'vellune.tree.expanded';
const loadExpanded = (): Set<string> => {
  try {
    return new Set<string>(JSON.parse(localStorage.getItem(EXP_KEY) || '[]'));
  } catch {
    return new Set();
  }
};

interface Row { page: Page; level: number; parent: string | null }
type DropPos = 'before' | 'after' | 'inside';

const MovePicker: React.FC<{ id: string; onClose: () => void; onExpand: (id: string) => void }> = ({ id, onClose, onExpand }) => {
  const pages = useStore((s) => s.pages);
  const [q, setQ] = React.useState('');
  const [sel, setSel] = React.useState(0);
  const prev = React.useRef(document.activeElement as HTMLElement | null);
  const inp = React.useRef<HTMLInputElement>(null);
  // Focus and restore in one effect so StrictMode's mount/unmount/mount cycle ends with the input focused.
  React.useEffect(() => {
    inp.current?.focus();
    return () => prev.current?.focus?.();
  }, []);
  const banned = React.useMemo(() => new Set([id, ...store.descendants(id)]), [id, pages]);
  const opts = React.useMemo(() => {
    const out: { id: string | null; title: string; level: number }[] = [{ id: null, title: 'Root', level: 0 }];
    const walk = (pid: string | null, level: number) => {
      for (const p of store.children(pid)) if (p.folder && !banned.has(p.id)) {
        out.push({ id: p.id, title: p.title || 'Untitled', level });
        walk(p.id, level + 1);
      }
    };
    walk(null, 1);
    const f = q.trim().toLowerCase();
    return f ? out.filter((o) => o.title.toLowerCase().includes(f)) : out;
  }, [pages, banned, q]);
  const pick = (o?: { id: string | null }) => {
    if (!o) return;
    store.move(id, o.id);
    if (o.id) onExpand(o.id);
    onClose();
  };
  return (
    <div className="move-overlay" onMouseDown={onClose}>
      <div
        className="popover move-picker"
        role="dialog"
        aria-label="Move to"
        onMouseDown={(e) => e.stopPropagation()}
        onKeyDown={(e) => {
          if (e.key === 'Escape') { e.preventDefault(); onClose(); }
          else if (e.key === 'ArrowDown') { e.preventDefault(); setSel((i) => Math.min(i + 1, opts.length - 1)); }
          else if (e.key === 'ArrowUp') { e.preventDefault(); setSel((i) => Math.max(i - 1, 0)); }
          else if (e.key === 'Enter') { e.preventDefault(); pick(opts[sel]); }
        }}
      >
        <input
          ref={inp}
          className="move-input"
          placeholder="Move to…"
          aria-label="Search folders"
          value={q}
          onChange={(e) => { setQ(e.target.value); setSel(0); }}
        />
        <div className="move-list" role="listbox" aria-label="Folders">
          {opts.map((o, i) => (
            <div
              key={o.id ?? 'root'}
              role="option"
              aria-selected={i === sel}
              className={`move-opt${i === sel ? ' sel' : ''}`}
              style={{ paddingLeft: 8 + o.level * 14 }}
              onMouseEnter={() => setSel(i)}
              onClick={() => pick(o)}
            >
              {o.id ? <Folder size={14} aria-hidden="true" /> : <FolderInput size={14} aria-hidden="true" />}
              <span className="page-title-text">{o.title}</span>
            </div>
          ))}
          {opts.length === 0 && <div className="side-empty">No folders</div>}
        </div>
      </div>
    </div>
  );
};

const OrganizeTree: React.FC<{ renamingRef: React.MutableRefObject<(id: string) => void>; rootProps: React.HTMLAttributes<HTMLDivElement>; setRootDropActive: (b: boolean) => void; rootDrop: boolean; dragRef: React.MutableRefObject<string | null> }> = ({ renamingRef, rootProps, rootDrop, setRootDropActive, dragRef }) => {
  const { pages, activeId, view } = useStore((s) => s);
  const [expanded, setExpanded] = React.useState<Set<string>>(loadExpanded);
  const [renaming, setRenaming] = React.useState<string | null>(null);
  const [focusId, setFocusId] = React.useState<string | null>(null);
  const [drop, setDrop] = React.useState<{ id: string; pos: DropPos } | null>(null);
  const [moving, setMoving] = React.useState<string | null>(null);
  const [sel, setSel] = React.useState<Set<string>>(new Set());
  const anchor = React.useRef<string | null>(null);
  const hoverTimer = React.useRef<ReturnType<typeof setTimeout>>(undefined);
  const hoverId = React.useRef<string | null>(null);
  const treeRef = React.useRef<HTMLDivElement>(null);
  renamingRef.current = setRenaming;

  const setExp = (id: string, on: boolean) =>
    setExpanded((prev) => {
      if (prev.has(id) === on) return prev;
      const n = new Set(prev);
      on ? n.add(id) : n.delete(id);
      try { localStorage.setItem(EXP_KEY, JSON.stringify([...n])); } catch { /* storage unavailable */ }
      return n;
    });

  const rows: Row[] = [];
  const seen = new Set<string>();
  const walk = (pid: string | null, level: number) => {
    for (const p of store.children(pid)) {
      if (seen.has(p.id)) continue;
      seen.add(p.id);
      rows.push({ page: p, level, parent: pid });
      if (expanded.has(p.id)) walk(p.id, level + 1);
    }
  };
  walk(null, 0);
  void pages;

  // Drop selected rows whose ancestor is also selected: trashing the ancestor already covers them.
  const topLevel = (ids: Set<string>) =>
    rows.map((r) => r.page.id).filter((id) => {
      if (!ids.has(id)) return false;
      for (let p = pages[id]?.parentId; p; p = pages[p]?.parentId) if (ids.has(p)) return false;
      return true;
    });
  const trashSelection = (ids: Set<string>) => {
    topLevel(ids).forEach((id) => store.trash(id));
    setSel(new Set());
  };
  /** Rows being dragged: the whole selection when the grabbed row is in it, else just that row. */
  const dragging = (d: string) => (sel.has(d) ? topLevel(sel) : [d]);

  /** Windows-style click: Ctrl/Cmd toggles, Shift selects a range, plain click selects only this row. */
  const select = (e: React.MouseEvent | React.KeyboardEvent, id: string) => {
    const i = rows.findIndex((r) => r.page.id === id);
    if (e.shiftKey && anchor.current) {
      const a = rows.findIndex((r) => r.page.id === anchor.current);
      if (a >= 0) {
        const [lo, hi] = a < i ? [a, i] : [i, a];
        const range = rows.slice(lo, hi + 1).map((r) => r.page.id);
        setSel((prev) => new Set(e.ctrlKey || e.metaKey ? [...prev, ...range] : range));
        return;
      }
    }
    anchor.current = id;
    setSel((prev) => {
      const n = new Set(e.ctrlKey || e.metaKey ? prev : []);
      n.has(id) ? n.delete(id) : n.add(id);
      return n;
    });
  };

  const focusRow = (id: string) => {
    setFocusId(id);
    requestAnimationFrame(() => treeRef.current?.querySelector<HTMLElement>(`[data-id="${id}"]`)?.focus());
  };
  const docActive = (id: string) => activeId === id && view !== 'graph' && view !== 'database' && view !== 'trash';

  const newDocIn = (parent?: string) => {
    store.createPage('Untitled', { parentId: parent });
    if (parent) setExp(parent, true);
  };
  const newFolderIn = (parent?: string) => {
    const f = store.createFolder('New folder', parent);
    if (parent) setExp(parent, true);
    setRenaming(f.id);
  };

  const menuFor = (p: Page): MenuEntry[] => {
    if (sel.size > 1 && sel.has(p.id)) {
      return [{ label: `Move ${sel.size} items to trash`, icon: Trash2, danger: true, hint: 'Del', onSelect: () => trashSelection(sel) }];
    }
    if (p.folder) {
      const n = store.descendants(p.id).length;
      return [
        { label: 'New doc', icon: FilePlus, onSelect: () => newDocIn(p.id) },
        { label: 'New folder', icon: FolderPlus, onSelect: () => newFolderIn(p.id) },
        { label: 'Rename', icon: Pencil, onSelect: () => setRenaming(p.id), hint: 'F2' },
        { label: 'Move to…', icon: FolderInput, onSelect: () => setMoving(p.id) },
        'separator',
        { label: 'Delete folder', icon: Trash2, danger: true, hint: `${n} item${n === 1 ? '' : 's'}`, onSelect: () => store.trash(p.id) },
      ];
    }
    const path = routePath({ view: 'page', pageId: p.id });
    return [
      { label: 'Open', icon: FileText, onSelect: () => store.open(p.id) },
      { label: 'Open in new tab', icon: ExternalLink, onSelect: () => window.open(path, '_blank', 'noopener') },
      { label: 'Copy link', icon: Link2, onSelect: () => void navigator.clipboard?.writeText(location.origin + path).catch(() => {}) },
      'separator',
      { label: 'New doc inside', icon: FilePlus, onSelect: () => newDocIn(p.id) },
      { label: 'Rename', icon: Pencil, onSelect: () => setRenaming(p.id), hint: 'F2' },
      { label: 'Duplicate', icon: Copy, onSelect: () => { const c = store.duplicate(p.id); if (c) store.open(c.id); } },
      p.favorite
        ? { label: 'Remove from favorites', icon: StarOff, onSelect: () => store.updatePage(p.id, (x) => ({ ...x, favorite: false })) }
        : { label: 'Add to favorites', icon: Star, onSelect: () => store.updatePage(p.id, (x) => ({ ...x, favorite: true })) },
      { label: 'Move to…', icon: FolderInput, onSelect: () => setMoving(p.id) },
      'separator',
      { label: 'Move to trash', icon: Trash2, danger: true, onSelect: () => store.trash(p.id) },
    ];
  };

  const clearDrag = () => {
    dragRef.current = null;
    setDrop(null);
    setRootDropActive(false);
    clearTimeout(hoverTimer.current);
    hoverId.current = null;
  };

  const valid = (dragId: string, parent: string | null) =>
    dragging(dragId).every((id) => id !== parent && !(parent && store.descendants(id).includes(parent)));

  /** Resolve the drop target (new parent + before-sibling) for a row and zone. */
  const resolve = (r: Row, pos: DropPos): { parent: string | null; before?: string } => {
    if (pos === 'inside') return { parent: r.page.id };
    if (pos === 'before') return { parent: r.parent, before: r.page.id };
    const moved = dragging(dragRef.current!);
    const sibs = store.children(r.parent).filter((p) => !moved.includes(p.id));
    return { parent: r.parent, before: sibs[sibs.findIndex((p) => p.id === r.page.id) + 1]?.id };
  };

  const zone = (e: React.DragEvent): DropPos => {
    const b = e.currentTarget.getBoundingClientRect();
    const y = (e.clientY - b.top) / b.height;
    return y < 0.25 ? 'before' : y > 0.75 ? 'after' : 'inside';
  };

  const onRowDragOver = (e: React.DragEvent, r: Row) => {
    const d = dragRef.current;
    if (!d) return;
    const pos = zone(e);
    if (dragging(d).includes(r.page.id) || !valid(d, resolve(r, pos).parent)) {
      setDrop(null);
      return;
    }
    e.preventDefault();
    e.stopPropagation();
    e.dataTransfer.dropEffect = 'move';
    setRootDropActive(false);
    setDrop((cur) => (cur?.id === r.page.id && cur.pos === pos ? cur : { id: r.page.id, pos }));
    if (pos === 'inside' && !expanded.has(r.page.id) && hoverId.current !== r.page.id) {
      hoverId.current = r.page.id;
      clearTimeout(hoverTimer.current);
      hoverTimer.current = setTimeout(() => setExp(r.page.id, true), 600);
    } else if (pos !== 'inside' && hoverId.current === r.page.id) {
      hoverId.current = null;
      clearTimeout(hoverTimer.current);
    }
  };

  const onRowDrop = (e: React.DragEvent, r: Row) => {
    const d = dragRef.current;
    if (!d) return;
    e.preventDefault();
    e.stopPropagation();
    const pos = zone(e);
    const t = resolve(r, pos);
    const ids = dragging(d);
    if (!ids.includes(r.page.id) && valid(d, t.parent)) {
      // Moving each before the same anchor keeps their on-screen order; with no anchor, append in order.
      const moved = ids.filter((id) => store.move(id, t.parent, t.before));
      if (moved.length && t.parent) setExp(t.parent, true);
    }
    clearDrag();
  };

  const commitRename = (p: Page, v: string) => {
    setRenaming(null);
    const t = v.trim();
    if (t && t !== p.title) store.updatePage(p.id, (x) => ({ ...x, title: t }));
    focusRow(p.id);
  };

  const onKeyDown = (e: React.KeyboardEvent) => {
    if ((e.target as HTMLElement).tagName === 'INPUT') return;
    if ((e.key === 'a' || e.key === 'A') && (e.ctrlKey || e.metaKey)) {
      e.preventDefault();
      setSel(new Set(rows.map((r) => r.page.id)));
      return;
    }
    if (e.key === 'Escape' && sel.size) { setSel(new Set()); return; }
    if ((e.key === 'Delete' || e.key === 'Backspace') && sel.size) {
      e.preventDefault();
      trashSelection(sel);
      return;
    }
    const id = (document.activeElement as HTMLElement)?.dataset?.id;
    const i = rows.findIndex((r) => r.page.id === id);
    if (i < 0) return;
    const r = rows[i];
    const hasKids = r.page.folder || store.children(r.page.id).length > 0;
    const stop = () => e.preventDefault();
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      stop();
      const nx = rows[i + (e.key === 'ArrowDown' ? 1 : -1)];
      if (!nx) return;
      if (e.shiftKey) {
        if (!anchor.current) anchor.current = r.page.id;
        const a = rows.findIndex((x) => x.page.id === anchor.current);
        const b = rows.findIndex((x) => x.page.id === nx.page.id);
        setSel(new Set(rows.slice(Math.min(a, b), Math.max(a, b) + 1).map((x) => x.page.id)));
      }
      focusRow(nx.page.id);
    }
    else if (e.key === 'Home') { stop(); focusRow(rows[0].page.id); }
    else if (e.key === 'End') { stop(); focusRow(rows[rows.length - 1].page.id); }
    else if (e.key === 'ArrowRight') {
      stop();
      if (hasKids && !expanded.has(r.page.id)) setExp(r.page.id, true);
      else if (rows[i + 1]?.parent === r.page.id) focusRow(rows[i + 1].page.id);
    } else if (e.key === 'ArrowLeft') {
      stop();
      if (hasKids && expanded.has(r.page.id)) setExp(r.page.id, false);
      else if (r.parent && rows.some((x) => x.page.id === r.parent)) focusRow(r.parent);
    } else if (e.key === 'Enter' || e.key === ' ') {
      stop();
      if (e.ctrlKey || e.metaKey) select(e, r.page.id);
      else r.page.folder ? setExp(r.page.id, !expanded.has(r.page.id)) : store.open(r.page.id);
    } else if (e.key === 'F2') { stop(); setRenaming(r.page.id); }
    else if (e.key === 'ContextMenu' || (e.key === 'F10' && e.shiftKey)) {
      stop();
      openContextMenuAt(document.activeElement as HTMLElement, menuFor(r.page));
    }
  };

  const tabId = rows.some((r) => r.page.id === focusId) ? focusId : rows[0]?.page.id;

  return (
    <>
      <div
        ref={treeRef}
        role="tree"
        aria-multiselectable="true"
        aria-label="Organize"
        className={`org-tree${rootDrop ? ' root-drop' : ''}`}
        onKeyDown={onKeyDown}
        {...rootProps}
      >
        {rows.map((r) => {
          const p = r.page;
          const isOpen = expanded.has(p.id);
          const hasKids = p.folder || store.children(p.id).length > 0;
          const Icon = p.folder ? (isOpen ? FolderOpen : Folder) : FileText;
          const cls = [
            'tree-row',
            !p.folder && docActive(p.id) ? 'active' : '',
            sel.has(p.id) ? 'selected' : '',
            drop?.id === p.id ? `drop-${drop.pos}` : '',
            dragRef.current && dragging(dragRef.current).includes(p.id) ? 'dragging' : '',
          ].join(' ');
          return (
            <div
              key={p.id}
              role="treeitem"
              data-id={p.id}
              aria-level={r.level + 1}
              aria-expanded={hasKids ? isOpen : undefined}
              aria-selected={sel.has(p.id) || (!p.folder && docActive(p.id))}
              tabIndex={p.id === tabId ? 0 : -1}
              draggable={renaming !== p.id}
              className={cls}
              style={{ paddingLeft: 6 + r.level * 14 }}
              onFocus={(e) => e.target === e.currentTarget && setFocusId(p.id)}
              onClick={(e) => {
                if (e.ctrlKey || e.metaKey || e.shiftKey) return select(e, p.id);
                anchor.current = p.id;
                setSel(new Set());
                p.folder ? setExp(p.id, !isOpen) : store.open(p.id);
              }}
              onContextMenu={(e) => {
                if (!sel.has(p.id)) setSel(new Set());
                openContextMenu(e, menuFor(p));
              }}
              onDragStart={(e) => {
                dragRef.current = p.id;
                e.dataTransfer.effectAllowed = 'move';
                e.dataTransfer.setData('text/plain', p.id);
                setFocusId(p.id);
              }}
              onDragEnd={clearDrag}
              onDragOver={(e) => onRowDragOver(e, r)}
              onDrop={(e) => onRowDrop(e, r)}
            >
              <span
                className={`tree-chev${hasKids ? '' : ' none'}`}
                onClick={(e) => { if (hasKids) { e.stopPropagation(); setExp(p.id, !isOpen); } }}
              >
                <ChevronRight size={12} className={isOpen ? 'caret open' : 'caret'} aria-hidden="true" />
              </span>
              {p.icon ? <PageIcon page={p} className="tree-icon" /> : <Icon size={16} aria-hidden="true" className="tree-icon" />}
              {renaming === p.id ? (
                <RenameInput initial={p.title} onDone={(v) => commitRename(p, v)} />
              ) : (
                <span className="page-title-text">{p.title || 'Untitled'}</span>
              )}
              {renaming !== p.id && (
                <span className="tree-actions" onClick={(e) => e.stopPropagation()}>
                  {p.folder && (
                    <button type="button" className="btn-icon" tabIndex={-1} title="New doc" aria-label={`New doc in ${p.title || 'Untitled'}`} onClick={() => newDocIn(p.id)}>
                      <Plus size={14} />
                    </button>
                  )}
                  <button
                    type="button"
                    className="btn-icon"
                    tabIndex={-1}
                    title="More"
                    aria-label={`More actions for ${p.title || 'Untitled'}`}
                    onClick={(e) => openContextMenuAt(e.currentTarget, menuFor(p))}
                  >
                    <MoreHorizontal size={14} />
                  </button>
                </span>
              )}
            </div>
          );
        })}
        {rows.length === 0 && <div className="side-empty">No docs yet</div>}
        <div className="tree-tail" />
      </div>
      {moving && pages[moving] && <MovePicker id={moving} onClose={() => setMoving(null)} onExpand={(f) => setExp(f, true)} />}
    </>
  );
};

const RenameInput: React.FC<{ initial: string; onDone: (v: string) => void }> = ({ initial, onDone }) => {
  const done = React.useRef(false);
  const finish = (v: string) => {
    if (done.current) return;
    done.current = true;
    onDone(v);
  };
  return (
    <input
      className="tree-rename"
      autoFocus
      defaultValue={initial}
      aria-label="Rename"
      onFocus={(e) => e.currentTarget.select()}
      onClick={(e) => e.stopPropagation()}
      onKeyDown={(e) => {
        e.stopPropagation();
        if (e.key === 'Enter') finish(e.currentTarget.value);
        else if (e.key === 'Escape') finish(initial);
      }}
      onBlur={(e) => finish(e.currentTarget.value)}
    />
  );
};

export const Sidebar: React.FC<{ onOpenSearch: () => void }> = ({ onOpenSearch }) => {
  const { pages, activeId, view, databaseTag } = useStore((s) => s);
  const [theme, setThemeState] = React.useState<Theme>(getTheme());

  const activePages = Object.values(pages).filter((p) => !p.deletedAt);
  const favoritePages = activePages.filter((p) => p.favorite && !p.folder);
  const trashedCount = Object.values(pages).filter((p) => p.deletedAt).length;

  // Extract all unique tags
  const tags = Array.from(new Set(activePages.flatMap((p) => p.tags))).sort();
  const [newTag, setNewTag] = React.useState(false);
  // Tags are derived from pages, so a new tag is created by tagging a fresh doc with it.
  const createTag = (raw: string) => {
    setNewTag(false);
    const t = raw.trim().replace(/^#+/, '').replace(/\s+/g, '-');
    if (!t) return;
    if (!tags.includes(t)) store.createPage('Untitled', { tags: [t] });
    store.setDatabaseTag(t);
  };

  const userName = useSession((st) => st.user?.name);
  const docActive = (id: string) => activeId === id && view !== 'graph' && view !== 'database' && view !== 'trash';
  const docRow = (p: (typeof activePages)[number]) => (
    <button
      type="button"
      key={p.id}
      className={`page-list-item ${docActive(p.id) ? 'active' : ''}`}
      onClick={() => store.open(p.id)}
    >
      <PageIcon page={p} />
      <span className="page-title-text">{p.title || 'Untitled'}</span>
    </button>
  );
  const ThemeIcon = THEME_ICON[theme];
  const dragRef = React.useRef<string | null>(null);
  const renamingRef = React.useRef<(id: string) => void>(() => {});
  const [rootDrop, setRootDrop] = React.useState(false);
  const newDoc = () => store.createPage('Untitled');
  const newFolder = () => renamingRef.current(store.createFolder('New folder').id);
  const orgMenu: MenuEntry[] = [
    { label: 'New doc', icon: FilePlus, onSelect: newDoc },
    { label: 'New folder', icon: FolderPlus, onSelect: newFolder },
  ];
  // Dropping on the header or the empty area moves to root (row handlers stop propagation first).
  const rootTarget = {
    onDragOver: (e: React.DragEvent) => {
      if (!dragRef.current) return;
      e.preventDefault();
      setRootDrop(true);
    },
    onDragLeave: (e: React.DragEvent) => {
      if (!e.currentTarget.contains(e.relatedTarget as Node)) setRootDrop(false);
    },
    onDrop: (e: React.DragEvent) => {
      e.preventDefault();
      if (dragRef.current) store.move(dragRef.current, null);
      dragRef.current = null;
      setRootDrop(false);
    },
  };

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

        <Section
          label="Organize"
          headProps={{ ...rootTarget, className: `section-head${rootDrop ? ' root-drop' : ''}` }}
          action={
            <button type="button" className="btn-icon section-add" title="New doc or folder" aria-label="Add to Organize" aria-haspopup="menu" onClick={(e) => openContextMenuAt(e.currentTarget, orgMenu)}>
              <Plus size={14} />
            </button>
          }
        >
          <OrganizeTree
            renamingRef={renamingRef}
            rootDrop={rootDrop}
            setRootDropActive={setRootDrop}
            dragRef={dragRef}
            rootProps={{ ...rootTarget, onContextMenu: (e) => openContextMenu(e, orgMenu) }}
          />
        </Section>

        {(
          <Section
            label="Tags"
            action={
              <button type="button" className="btn-icon section-add" title="New tag" aria-label="New tag" onClick={() => setNewTag(true)}>
                <Plus size={14} />
              </button>
            }
          >
            {newTag && (
              <div className="page-list-item">
                <Hash size={16} aria-hidden="true" />
                <input
                  className="tree-rename"
                  autoFocus
                  placeholder="New tag"
                  aria-label="New tag name"
                  onBlur={(e) => createTag(e.currentTarget.value)}
                  onKeyDown={(e) => {
                    if (e.key !== 'Enter' && e.key !== 'Escape') return;
                    const v = e.key === 'Enter' ? e.currentTarget.value : '';
                    e.currentTarget.value = ''; // blur fires on unmount; keep it a no-op
                    createTag(v);
                  }}
                />
              </div>
            )}
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
        <Account />
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
