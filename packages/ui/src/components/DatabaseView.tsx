import React, { useState } from 'react';
import { Columns3, Table, Plus } from 'lucide-react';
import { store, useStore } from '../store';
import { buildIndex } from '../links';

export const DatabaseView: React.FC = () => {
  const { pages, databaseTag } = useStore((s) => s);
  const [layout, setLayout] = useState<'table' | 'kanban'>('table');

  const activePages = Object.values(pages).filter((p) => !p.deletedAt && !p.folder);
  const index = buildIndex(activePages);

  // Filter by tag if selected in sidebar
  const filteredPages = databaseTag
    ? activePages.filter((p) => p.tags.includes(databaseTag))
    : activePages;

  // Extract tags for Kanban columns
  const allTags = Array.from(new Set(activePages.flatMap((p) => p.tags))).sort();
  const columns = ['Untagged', ...allTags];

  return (
    <div className="database-container">
      <div className="database-header">
        <div>
          <h2 style={{ margin: '0 0 4px 0', fontSize: '30px', fontWeight: 700, lineHeight: 1.2 }}>
            {databaseTag ? `#${databaseTag} Collection` : 'All Pages Database'}
          </h2>
          <span style={{ fontSize: '0.85rem', color: 'var(--text-muted)' }}>
            {filteredPages.length} pages in this view
          </span>
        </div>

        <div style={{ display: 'flex', gap: '0.5rem' }}>
          <div className="view-tabs">
            <button
              className={`view-tab ${layout === 'table' ? 'active' : ''}`}
              onClick={() => setLayout('table')}
            >
              <Table size={15} />
              <span>Table</span>
            </button>
            <button
              className={`view-tab ${layout === 'kanban' ? 'active' : ''}`}
              onClick={() => setLayout('kanban')}
            >
              <Columns3 size={15} />
              <span>Kanban</span>
            </button>
          </div>

          <button
            className="db-btn"
            onClick={() => store.createPage('New Page', databaseTag ? { tags: [databaseTag] } : {})}
          >
            <Plus size={16} />
            <span>New Note</span>
          </button>
        </div>
      </div>

      {layout === 'table' ? (
        <div className="db-wrap">
          <table className="db-table">
            <thead>
              <tr>
                <th>Title</th>
                <th>Tags</th>
                <th>Backlinks</th>
                <th>Updated</th>
              </tr>
            </thead>
            <tbody>
              {filteredPages.map((p) => {
                const backlinks = index.backlinks.get(p.id) ?? [];
                return (
                  <tr key={p.id}>
                    <td>
                      <span
                        onClick={() => store.open(p.id, 'page')}
                        style={{ fontWeight: 500, color: 'var(--text-primary)', cursor: 'pointer' }}
                      >
                        {p.title || 'Untitled'}
                      </span>
                    </td>
                    <td>
                      <div style={{ display: 'flex', gap: '4px', flexWrap: 'wrap' }}>
                        {p.tags.map((t) => (
                          <span key={t} className="tag-badge">#{t}</span>
                        ))}
                      </div>
                    </td>
                    <td>
                      <span style={{ color: backlinks.length > 0 ? 'var(--text-primary)' : 'var(--text-muted)' }}>
                        {backlinks.length}
                      </span>
                    </td>
                    <td>
                      <span style={{ color: 'var(--text-muted)', fontSize: '0.8rem' }}>
                        {new Date(p.updatedAt).toLocaleDateString()}
                      </span>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      ) : (
        /* Kanban Board View */
        <div className="kanban-board">
          {columns.map((colTag) => {
            const isUntagged = colTag === 'Untagged';
            const colPages = filteredPages.filter((p) =>
              isUntagged ? p.tags.length === 0 : p.tags.includes(colTag)
            );

            return (
              <div key={colTag} className="kanban-col">
                <div className="kanban-col-header">
                  <span>{isUntagged ? 'No Tags' : `#${colTag}`}</span>
                  <span style={{ color: 'var(--text-muted)', fontSize: '0.8rem' }}>
                    {colPages.length}
                  </span>
                </div>

                {colPages.map((page) => (
                  <div
                    key={page.id}
                    className="kanban-card"
                    onClick={() => store.open(page.id, 'page')}
                  >
                    <div style={{ fontWeight: 600, fontSize: '0.9rem', marginBottom: '0.4rem' }}>
                      {page.title || 'Untitled'}
                    </div>
                    <div style={{ fontSize: '0.78rem', color: 'var(--text-muted)', maxHeight: 36, overflow: 'hidden' }}>
                      {page.blocks[0]?.text || 'No content...'}
                    </div>
                  </div>
                ))}

                <button
                  onClick={() => store.createPage('New Page', isUntagged ? {} : { tags: [colTag] })}
                  style={{
                    background: 'transparent',
                    border: '1px dashed var(--border-color)',
                    borderRadius: '6px',
                    padding: '0.4rem',
                    color: 'var(--text-muted)',
                    fontSize: '0.8rem',
                    cursor: 'pointer',
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    gap: '0.3rem',
                  }}
                >
                  <Plus size={14} />
                  <span>Add card</span>
                </button>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
};
