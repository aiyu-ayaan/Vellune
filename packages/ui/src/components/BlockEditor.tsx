import React, { useEffect, useRef, useState } from 'react';
import { Check, ChevronRight, GripVertical, Trash2 } from 'lucide-react';
import { Block, BlockKind, NotionColor, NotionTextColor, Page, newBlock } from '../model';
import { store, useStore } from '../store';
import { buildIndex } from '../links';
import { useSync } from '../cloud/sync';

const PLACEHOLDER = "Type '# ' or '- ' for formats, '[[' to link";

const TURN_INTO: { label: string; kind: BlockKind; level?: number }[] = [
  { label: 'Text', kind: 'paragraph' },
  { label: 'Heading 1', kind: 'heading', level: 1 },
  { label: 'Heading 2', kind: 'heading', level: 2 },
  { label: 'Heading 3', kind: 'heading', level: 3 },
  { label: 'To-do list', kind: 'todo' },
  { label: 'Bulleted list', kind: 'bullet' },
  { label: 'Quote', kind: 'quote' },
  { label: 'Code', kind: 'code' },
  { label: 'Divider', kind: 'divider' },
];

const TEXT_COLORS: NotionTextColor[] = ['gray', 'brown', 'orange', 'yellow', 'green', 'blue', 'purple', 'pink', 'red'];
const cap = (s: string) => s.charAt(0).toUpperCase() + s.slice(1);

const BlockMenu: React.FC<{
  block: Block;
  onTurn: (t: { kind: BlockKind; level?: number }) => void;
  onColor: (c: NotionColor) => void;
  onDelete: () => void;
  onClose: () => void;
}> = ({ block, onTurn, onColor, onDelete, onClose }) => {
  const [sub, setSub] = useState<'turn' | 'color' | null>(null);
  const ref = useRef<HTMLDivElement>(null);
  const current: NotionColor = block.color ?? 'default';

  useEffect(() => {
    ref.current?.querySelector<HTMLElement>('button')?.focus();
    const onDown = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) onClose();
    };
    document.addEventListener('mousedown', onDown);
    return () => document.removeEventListener('mousedown', onDown);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sub]);

  const onKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Escape') {
      e.stopPropagation();
      if (sub) setSub(null);
      else onClose();
      return;
    }
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault();
      const items = Array.from(ref.current?.querySelectorAll<HTMLElement>('button') ?? []);
      const i = items.indexOf(document.activeElement as HTMLElement);
      const next = e.key === 'ArrowDown' ? (i + 1) % items.length : (i - 1 + items.length) % items.length;
      items[next]?.focus();
    }
  };

  const colorItem = (c: NotionColor, label: string, bg: boolean, key: NotionColor) => (
    <button
      key={key}
      type="button"
      role="menuitemradio"
      aria-checked={current === key}
      className="menu-item"
      onClick={() => onColor(key)}
    >
      <span
        className="swatch"
        style={
          c === 'default'
            ? undefined
            : bg
              ? { background: `var(--nb-${c.replace('_background', '')})` }
              : { color: `var(--nc-${c})` }
        }
        aria-hidden="true"
      >
        A
      </span>
      <span>{label}</span>
      {current === key && <span className="menu-end"><Check size={14} aria-hidden="true" /></span>}
    </button>
  );

  return (
    <div className="popover" role="menu" aria-label="Block menu" ref={ref} onKeyDown={onKeyDown} style={{ left: 8, top: 30 }}>
      {sub === null && (
        <>
          <button type="button" role="menuitem" className="menu-item" onClick={() => setSub('turn')} aria-haspopup="menu">
            <span>Turn into</span>
            <span className="menu-end"><ChevronRight size={14} aria-hidden="true" /></span>
          </button>
          <button type="button" role="menuitem" className="menu-item" onClick={() => setSub('color')} aria-haspopup="menu">
            <span>Color</span>
            <span className="menu-end"><ChevronRight size={14} aria-hidden="true" /></span>
          </button>
          <div className="popover-sep" />
          <button type="button" role="menuitem" className="menu-item" onClick={onDelete} style={{ color: 'var(--danger)' }}>
            <Trash2 size={14} aria-hidden="true" />
            <span>Delete</span>
          </button>
        </>
      )}
      {sub === 'turn' && (
        <>
          <div className="popover-label">Turn into</div>
          {TURN_INTO.map((t) => (
            <button key={t.label} type="button" role="menuitem" className="menu-item" onClick={() => onTurn(t)}>
              <span>{t.label}</span>
              {block.kind === t.kind && (t.kind !== 'heading' || (block.level ?? 1) === t.level) && (
                <span className="menu-end"><Check size={14} aria-hidden="true" /></span>
              )}
            </button>
          ))}
        </>
      )}
      {sub === 'color' && (
        <>
          <div className="popover-label">Text color</div>
          {colorItem('default', 'Default', false, 'default')}
          {TEXT_COLORS.map((c) => colorItem(c, cap(c), false, c))}
          <div className="popover-label">Background color</div>
          {colorItem('default', 'Default background', true, 'default')}
          {TEXT_COLORS.map((c) => colorItem(`${c}_background`, `${cap(c)} background`, true, `${c}_background`))}
        </>
      )}
    </div>
  );
};

export const BlockEditor: React.FC<{ page: Page }> = ({ page }) => {
  const { pages } = useStore((s) => s);
  const readOnly = useSync((s) => s.roles[page.id] === 'viewer');
  const [activeBlockId, setActiveBlockId] = useState<string | null>(null);
  const [newTagInput, setNewTagInput] = useState('');
  const [showTagForm, setShowTagForm] = useState(false);
  const [menuBlockId, setMenuBlockId] = useState<string | null>(null);
  const handleRefs = useRef<Record<string, HTMLButtonElement | null>>({});
  const blockRefs = useRef<Record<string, HTMLTextAreaElement | null>>({});

  const index = buildIndex(Object.values(pages));

  const updateBlock = (blockId: string, text: string) => {
    store.updatePage(page.id, (p) => {
      const blocks = p.blocks.map((b) => {
        if (b.id !== blockId) return b;

        // Check markdown shortcuts on text changes
        if (text.startsWith('# ')) {
          return { ...b, kind: 'heading' as BlockKind, level: 1, text: text.slice(2) };
        }
        if (text.startsWith('## ')) {
          return { ...b, kind: 'heading' as BlockKind, level: 2, text: text.slice(3) };
        }
        if (text.startsWith('### ')) {
          return { ...b, kind: 'heading' as BlockKind, level: 3, text: text.slice(4) };
        }
        if (text.startsWith('- [ ] ')) {
          return { ...b, kind: 'todo' as BlockKind, checked: false, text: text.slice(6) };
        }
        if (text.startsWith('- [x] ')) {
          return { ...b, kind: 'todo' as BlockKind, checked: true, text: text.slice(6) };
        }
        if (text.startsWith('- ')) {
          return { ...b, kind: 'bullet' as BlockKind, text: text.slice(2) };
        }
        if (text.startsWith('> ')) {
          return { ...b, kind: 'quote' as BlockKind, text: text.slice(2) };
        }
        if (text.startsWith('```')) {
          return { ...b, kind: 'code' as BlockKind, text: text.slice(3) };
        }
        if (text.startsWith('---')) {
          return { ...b, kind: 'divider' as BlockKind, text: '' };
        }

        return { ...b, text };
      });
      return { ...p, blocks };
    });
  };

  const handleKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>, blockIndex: number, block: Block) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      const newB = newBlock('paragraph', '');
      store.updatePage(page.id, (p) => {
        const nextBlocks = [...p.blocks];
        nextBlocks.splice(blockIndex + 1, 0, newB);
        return { ...p, blocks: nextBlocks };
      });
      setTimeout(() => {
        blockRefs.current[newB.id]?.focus();
      }, 10);
    } else if (e.key === 'Backspace' && block.text === '' && page.blocks.length > 1) {
      e.preventDefault();
      store.updatePage(page.id, (p) => ({
        ...p,
        blocks: p.blocks.filter((b) => b.id !== block.id),
      }));
      const prevBlock = page.blocks[blockIndex - 1];
      if (prevBlock) {
        setTimeout(() => {
          blockRefs.current[prevBlock.id]?.focus();
        }, 10);
      }
    } else if (e.key === 'ArrowUp' && blockIndex > 0) {
      const prevBlock = page.blocks[blockIndex - 1];
      if (prevBlock) blockRefs.current[prevBlock.id]?.focus();
    } else if (e.key === 'ArrowDown' && blockIndex < page.blocks.length - 1) {
      const nextBlock = page.blocks[blockIndex + 1];
      if (nextBlock) blockRefs.current[nextBlock.id]?.focus();
    }
  };

  const patchBlock = (id: string, patch: (b: Block) => Block) =>
    store.updatePage(page.id, (p) => ({ ...p, blocks: p.blocks.map((b) => (b.id === id ? patch(b) : b)) }));

  const closeMenu = (refocus = true) => {
    const id = menuBlockId;
    setMenuBlockId(null);
    if (refocus && id) setTimeout(() => handleRefs.current[id]?.focus(), 0);
  };

  const handleWikilinkClick = (target: string) => {
    store.openOrCreate(target, (t) => index.resolve(t));
  };

  // Render text with clickable [[wikilinks]]
  const renderTextWithWikilinks = (text: string) => {
    const parts: React.ReactNode[] = [];
    const regex = /\[\[([^\]|]+)(?:\|([^\]]+))?\]\]/g;
    let lastIndex = 0;
    let match;

    while ((match = regex.exec(text)) !== null) {
      if (match.index > lastIndex) {
        parts.push(text.slice(lastIndex, match.index));
      }
      const target = match[1].trim();
      const label = (match[2] ?? target).trim();
      parts.push(
        <span
          key={`${match.index}-${target}`}
          className="wikilink-tag"
          onClick={(e) => {
            e.stopPropagation();
            handleWikilinkClick(target);
          }}
          title={`Open note: [[${target}]]`}
        >
          {label}
        </span>
      );
      lastIndex = regex.lastIndex;
    }

    if (lastIndex < text.length) {
      parts.push(text.slice(lastIndex));
    }

    return parts.length > 0 ? parts : text;
  };

  const addTag = () => {
    const tag = newTagInput.trim().replace(/^#/, '');
    if (!tag) return;
    if (!page.tags.includes(tag)) {
      store.updatePage(page.id, (p) => ({ ...p, tags: [...p.tags, tag] }));
    }
    setNewTagInput('');
    setShowTagForm(false);
  };

  const removeTag = (tag: string) => {
    store.updatePage(page.id, (p) => ({ ...p, tags: p.tags.filter((t) => t !== tag) }));
  };

  return (
    <div className="editor-scroll-area">
      <div className="editor-center">
        {readOnly && <div className="read-only-badge">View only — the owner shared this page with you as a viewer</div>}
        {/* A disabled fieldset makes every control read-only for viewers in one place. */}
        <fieldset className="editor-fieldset" disabled={readOnly}>
        {/* Document Title */}
        <input
          className="doc-title-input"
          value={page.title}
          placeholder="Untitled"
          onChange={(e) => store.updatePage(page.id, (p) => ({ ...p, title: e.target.value }))}
        />

        {/* Page Properties */}
        <div className="page-properties">
          <div className="property-row">
            <span className="property-label">Tags</span>
            <div className="property-tags">
              {page.tags.map((t) => (
                <span key={t} className="tag-badge">
                  #{t}
                  <button onClick={() => removeTag(t)} aria-label={`Remove tag ${t}`}>
                    ×
                  </button>
                </span>
              ))}
              {showTagForm ? (
                <div style={{ display: 'flex', gap: '4px' }}>
                  <input
                    type="text"
                    value={newTagInput}
                    onChange={(e) => setNewTagInput(e.target.value)}
                    placeholder="tag"
                    onKeyDown={(e) => e.key === 'Enter' && addTag()}
                    className="tag-input"
                    aria-label="New tag"
                    autoFocus
                  />
                  <button className="tag-add" onClick={addTag}>Add</button>
                </div>
              ) : (
                <button
                  className="tag-add"
                  onClick={() => setShowTagForm(true)}
                >
                  + Add tag
                </button>
              )}
            </div>
          </div>
        </div>

        {/* Blocks Area */}
        <div className="blocks-container">
          {page.blocks.map((block, idx) => {
            const isEditing = activeBlockId === block.id;

            return (
              <div
                key={block.id}
                className={`block-row block-${block.kind} ${block.kind === 'heading' ? `block-heading-${block.level ?? 1}` : ''}`}
                onClick={() => {
                  setActiveBlockId(block.id);
                  blockRefs.current[block.id]?.focus();
                }}
              >
                <div className={`block-handle-wrap ${menuBlockId === block.id ? 'open' : ''}`}>
                  <button
                    type="button"
                    className="block-handle"
                    aria-label="Open block menu"
                    aria-haspopup="menu"
                    aria-expanded={menuBlockId === block.id}
                    ref={(el) => { handleRefs.current[block.id] = el; }}
                    onClick={(e) => {
                      e.stopPropagation();
                      setMenuBlockId(menuBlockId === block.id ? null : block.id);
                    }}
                  >
                    <GripVertical size={16} aria-hidden="true" />
                  </button>
                </div>

                {menuBlockId === block.id && (
                  <div onClick={(e) => e.stopPropagation()}>
                    <BlockMenu
                      block={block}
                      onClose={() => closeMenu()}
                      onTurn={(t) => {
                        patchBlock(block.id, (b) => ({
                          ...b,
                          kind: t.kind,
                          level: t.kind === 'heading' ? t.level : undefined,
                          text: t.kind === 'divider' ? '' : b.text,
                        }));
                        closeMenu();
                      }}
                      onColor={(c) => {
                        patchBlock(block.id, (b) => ({ ...b, color: c === 'default' ? undefined : c }));
                        closeMenu();
                      }}
                      onDelete={() => {
                        setMenuBlockId(null);
                        store.updatePage(page.id, (p) => ({
                          ...p,
                          blocks: p.blocks.length > 1 ? p.blocks.filter((b) => b.id !== block.id) : [newBlock()],
                        }));
                      }}
                    />
                  </div>
                )}

                <div className={`block-content${block.color ? ` nc-${block.color}` : ''}`}>
                  {block.kind === 'todo' && (
                    <div className={`block-todo ${block.checked ? 'done' : ''}`}>
                      <input
                        type="checkbox"
                        aria-label="Completed"
                        checked={block.checked ?? false}
                        onChange={(e) => {
                          store.updatePage(page.id, (p) => ({
                            ...p,
                            blocks: p.blocks.map((b) => (b.id === block.id ? { ...b, checked: e.target.checked } : b)),
                          }));
                        }}
                      />
                      <textarea
                        ref={(el) => { blockRefs.current[block.id] = el; }}
                        className="block-input"
                        rows={1}
                        value={block.text}
                        onChange={(e) => updateBlock(block.id, e.target.value)}
                        onKeyDown={(e) => handleKeyDown(e, idx, block)}
                        onFocus={() => setActiveBlockId(block.id)}
                        onBlur={() => setActiveBlockId(null)}
                        placeholder={PLACEHOLDER}
                      />
                    </div>
                  )}

                  {block.kind === 'bullet' && (
                    <div className="block-bullet">
                      <span className="bullet-dot">•</span>
                      <textarea
                        ref={(el) => { blockRefs.current[block.id] = el; }}
                        className="block-input"
                        rows={1}
                        value={block.text}
                        onChange={(e) => updateBlock(block.id, e.target.value)}
                        onKeyDown={(e) => handleKeyDown(e, idx, block)}
                        onFocus={() => setActiveBlockId(block.id)}
                        onBlur={() => setActiveBlockId(null)}
                        placeholder={PLACEHOLDER}
                      />
                    </div>
                  )}

                  {block.kind === 'divider' && <hr className="block-divider" />}

                  {block.kind !== 'todo' && block.kind !== 'bullet' && block.kind !== 'divider' && (
                    <textarea
                      ref={(el) => { blockRefs.current[block.id] = el; }}
                      className="block-input"
                      rows={block.kind === 'code' ? 3 : 1}
                      value={block.text}
                      onChange={(e) => updateBlock(block.id, e.target.value)}
                      onKeyDown={(e) => handleKeyDown(e, idx, block)}
                      onFocus={() => setActiveBlockId(block.id)}
                      onBlur={() => setActiveBlockId(null)}
                      placeholder={PLACEHOLDER}
                    />
                  )}

                  {/* Render preview wikilinks when not actively typing */}
                  {!isEditing && block.text.includes('[[') && (
                    <div className="wikilink-preview">
                      {renderTextWithWikilinks(block.text)}
                    </div>
                  )}
                </div>
              </div>
            );
          })}
        </div>
        </fieldset>
      </div>
    </div>
  );
};
