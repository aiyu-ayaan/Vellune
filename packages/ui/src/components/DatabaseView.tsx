import React, { useMemo, useState } from 'react';
import { ArrowDown, ArrowUp, Columns3, Plus, Search, Table } from 'lucide-react';
import { store, useStore } from '../store';
import { buildIndex } from '../links';
import { Page, PropValue, propKind, todayKey } from '../model';
import { compareProps, propNames, textValues } from '../props';
import { PropInput, kindIcon } from './PropsEditor';
import { PageIcon } from './PageIcon';

type SortKey = 'title' | 'updated' | 'backlinks' | `prop:${string}`;
const NO_VALUE = '\u0000none';

const defaultFor = (sample: PropValue | undefined): PropValue => {
  const k = propKind(sample);
  return k === 'checkbox' ? false : k === 'number' ? 0 : k === 'date' ? todayKey() : '';
};

export const DatabaseView: React.FC = () => {
  const { pages, databaseTag } = useStore((s) => s);
  const [layout, setLayout] = useState<'table' | 'kanban'>('table');
  const [query, setQuery] = useState('');
  const [sort, setSort] = useState<{ key: SortKey; dir: 1 | -1 }>({ key: 'updated', dir: -1 });
  const [groupBy, setGroupBy] = useState<string>('tags');
  const [dragId, setDragId] = useState<string | null>(null);

  const activePages = useMemo(() => Object.values(pages).filter((p) => !p.deletedAt && !p.folder), [pages]);
  const index = useMemo(() => buildIndex(activePages), [activePages]);
  const scoped = databaseTag ? activePages.filter((p) => p.tags.includes(databaseTag)) : activePages;
  const names = useMemo(() => propNames(scoped), [scoped]);
  const suggestions = useMemo(() => textValues(activePages), [activePages]);
  /** A value of each property from any page, to know its kind for empty cells. */
  const sample = useMemo(() => {
    const s: Record<string, PropValue> = {};
    for (const p of scoped) for (const [k, v] of Object.entries(p.props ?? {})) if (!(k in s)) s[k] = v;
    return s;
  }, [scoped]);

  const q = query.trim().toLowerCase();
  const filtered = scoped.filter(
    (p) => !q || p.title.toLowerCase().includes(q) || p.tags.some((t) => t.toLowerCase().includes(q)) || Object.values(p.props ?? {}).some((v) => String(v).toLowerCase().includes(q)),
  );
  const value = (p: Page, key: SortKey): PropValue | undefined =>
    key === 'title' ? p.title.toLowerCase() : key === 'updated' ? p.updatedAt : key === 'backlinks' ? (index.backlinks.get(p.id)?.length ?? 0) : p.props?.[key.slice(5)];
  const rows = [...filtered].sort((a, b) => sort.dir * compareProps(value(a, sort.key), value(b, sort.key)) || b.updatedAt - a.updatedAt);

  const setProp = (id: string, key: string, v: PropValue | undefined) =>
    store.updatePage(id, (p) => {
      const next = { ...(p.props ?? {}) };
      if (v === undefined) delete next[key];
      else next[key] = v;
      return { ...p, props: Object.keys(next).length ? next : undefined };
    });

  const header = (key: SortKey, label: React.ReactNode) => (
    <th aria-sort={sort.key === key ? (sort.dir === 1 ? 'ascending' : 'descending') : undefined}>
      <button type="button" className="db-sort" onClick={() => setSort((s) => ({ key, dir: s.key === key ? (s.dir === 1 ? -1 : 1) : 1 }))}>
        {label}
        {sort.key === key && (sort.dir === 1 ? <ArrowUp size={12} aria-hidden="true" /> : <ArrowDown size={12} aria-hidden="true" />)}
      </button>
    </th>
  );

  // Kanban columns: tags, or the values of a property (checkboxes are Yes/No).
  const groupable = names.filter((n) => ['text', 'checkbox'].includes(propKind(sample[n])));
  const byProp = groupBy !== 'tags' && groupable.includes(groupBy);
  const columns: { key: string; label: string }[] = byProp
    ? propKind(sample[groupBy]) === 'checkbox'
      ? [
          { key: 'true', label: 'Yes' },
          { key: 'false', label: 'No' },
        ]
      : [{ key: NO_VALUE, label: 'No value' }, ...[...new Set(scoped.map((p) => p.props?.[groupBy]).filter((v): v is string => typeof v === 'string' && !!v))].sort().map((v) => ({ key: v, label: v }))]
    : [{ key: NO_VALUE, label: 'No tags' }, ...[...new Set(scoped.flatMap((p) => p.tags))].sort().map((t) => ({ key: t, label: `#${t}` }))];
  const inColumn = (p: Page, col: string) => {
    if (!byProp) return col === NO_VALUE ? p.tags.length === 0 : p.tags.includes(col);
    const v = p.props?.[groupBy];
    if (propKind(sample[groupBy]) === 'checkbox') return String(v === true) === col;
    return col === NO_VALUE ? v === undefined || v === '' : v === col;
  };
  const moveTo = (p: Page, col: string) => {
    if (!byProp) return; // tags are many-to-many; drag only moves along a property
    if (propKind(sample[groupBy]) === 'checkbox') setProp(p.id, groupBy, col === 'true');
    else setProp(p.id, groupBy, col === NO_VALUE ? undefined : col);
  };
  const newIn = (col: string) => {
    const init: Partial<Page> = { tags: databaseTag ? [databaseTag] : [] };
    if (!byProp && col !== NO_VALUE) init.tags = [...new Set([...(init.tags ?? []), col])];
    if (byProp && col !== NO_VALUE) init.props = { [groupBy]: propKind(sample[groupBy]) === 'checkbox' ? col === 'true' : col };
    store.createPage('', init);
  };

  return (
    <div className="database-container">
      <div className="database-header">
        <div>
          <h2 style={{ margin: '0 0 4px 0', fontSize: '30px', fontWeight: 700, lineHeight: 1.2 }}>{databaseTag ? `#${databaseTag}` : 'All pages'}</h2>
          <span style={{ fontSize: '0.85rem', color: 'var(--text-muted)' }}>
            {filtered.length} of {scoped.length} pages
          </span>
        </div>

        <div className="db-controls">
          <label className="db-search">
            <Search size={14} aria-hidden="true" />
            <input value={query} onChange={(e) => setQuery(e.target.value)} placeholder="Filter" aria-label="Filter pages" />
          </label>
          {layout === 'kanban' && (
            <label className="db-group">
              Group by
              <select value={byProp ? groupBy : 'tags'} onChange={(e) => setGroupBy(e.target.value)}>
                <option value="tags">Tags</option>
                {groupable.map((n) => (
                  <option key={n} value={n}>
                    {n}
                  </option>
                ))}
              </select>
            </label>
          )}
          <div className="view-tabs" role="group" aria-label="Layout">
            <button type="button" className={`view-tab ${layout === 'table' ? 'active' : ''}`} aria-pressed={layout === 'table'} onClick={() => setLayout('table')}>
              <Table size={15} aria-hidden="true" />
              <span>Table</span>
            </button>
            <button type="button" className={`view-tab ${layout === 'kanban' ? 'active' : ''}`} aria-pressed={layout === 'kanban'} onClick={() => setLayout('kanban')}>
              <Columns3 size={15} aria-hidden="true" />
              <span>Board</span>
            </button>
          </div>
          <button type="button" className="db-btn" onClick={() => store.createPage('', databaseTag ? { tags: [databaseTag] } : {})}>
            <Plus size={16} aria-hidden="true" />
            <span>New</span>
          </button>
        </div>
      </div>

      {layout === 'table' ? (
        <div className="db-wrap">
          <table className="db-table">
            <thead>
              <tr>
                {header('title', 'Title')}
                <th>Tags</th>
                {names.map((n) => {
                  const Icon = kindIcon(sample[n]);
                  return (
                    <React.Fragment key={n}>
                      {header(`prop:${n}`, (
                        <>
                          <Icon size={12} aria-hidden="true" /> {n}
                        </>
                      ))}
                    </React.Fragment>
                  );
                })}
                {header('backlinks', 'Backlinks')}
                {header('updated', 'Updated')}
              </tr>
            </thead>
            <tbody>
              {rows.map((p) => (
                <tr key={p.id}>
                  <td>
                    <button type="button" className="db-title" onClick={() => store.open(p.id, 'page')}>
                      <PageIcon page={p} size={14} />
                      {p.title || 'Untitled'}
                    </button>
                  </td>
                  <td>
                    <div style={{ display: 'flex', gap: '4px', flexWrap: 'wrap' }}>
                      {p.tags.map((t) => (
                        <span key={t} className="tag-badge">
                          #{t}
                        </span>
                      ))}
                    </div>
                  </td>
                  {names.map((n) => (
                    <td key={n} className="db-prop">
                      {p.props && n in p.props ? (
                        <PropInput value={p.props[n]} name={`${n} of ${p.title || 'Untitled'}`} suggestions={suggestions[n]} onChange={(v) => setProp(p.id, n, v)} />
                      ) : (
                        <button type="button" className="db-empty" aria-label={`Set ${n}`} onClick={() => setProp(p.id, n, defaultFor(sample[n]))}>
                          —
                        </button>
                      )}
                    </td>
                  ))}
                  <td>
                    <span style={{ color: index.backlinks.get(p.id)?.length ? 'var(--text-primary)' : 'var(--text-muted)' }}>{index.backlinks.get(p.id)?.length ?? 0}</span>
                  </td>
                  <td>
                    <span style={{ color: 'var(--text-muted)', fontSize: '0.8rem' }}>{new Date(p.updatedAt).toLocaleDateString()}</span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        <div className="kanban-board">
          {columns.map((col) => {
            const cards = rows.filter((p) => inColumn(p, col.key));
            return (
              <div
                key={col.key}
                className={`kanban-col${dragId && byProp ? ' droppable' : ''}`}
                onDragOver={(e) => byProp && dragId && e.preventDefault()}
                onDrop={(e) => {
                  e.preventDefault();
                  const p = dragId ? pages[dragId] : undefined;
                  if (p) moveTo(p, col.key);
                  setDragId(null);
                }}
              >
                <div className="kanban-col-header">
                  <span>{col.label}</span>
                  <span style={{ color: 'var(--text-muted)', fontSize: '0.8rem' }}>{cards.length}</span>
                </div>
                {cards.map((p) => (
                  <div
                    key={p.id}
                    className="kanban-card"
                    draggable={byProp}
                    onDragStart={(e) => {
                      e.dataTransfer.effectAllowed = 'move';
                      setDragId(p.id);
                    }}
                    onDragEnd={() => setDragId(null)}
                    onClick={() => store.open(p.id, 'page')}
                  >
                    <div style={{ fontWeight: 600, fontSize: '0.9rem', marginBottom: '0.4rem', display: 'flex', gap: 6, alignItems: 'center' }}>
                      <PageIcon page={p} size={14} />
                      {p.title || 'Untitled'}
                    </div>
                    <div style={{ fontSize: '0.78rem', color: 'var(--text-muted)', maxHeight: 36, overflow: 'hidden' }}>{p.blocks.find((b) => b.text)?.text || 'No content…'}</div>
                  </div>
                ))}
                <button type="button" className="kanban-add" onClick={() => newIn(col.key)}>
                  <Plus size={14} aria-hidden="true" />
                  <span>New</span>
                </button>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
};
