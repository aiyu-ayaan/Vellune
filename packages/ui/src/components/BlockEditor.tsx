import React, { useRef, useState } from 'react';
import { GripVertical } from 'lucide-react';
import { Block, BlockKind, Page, newBlock } from '../model';
import { store, useStore } from '../store';
import { buildIndex } from '../links';

export const BlockEditor: React.FC<{ page: Page }> = ({ page }) => {
  const { pages } = useStore((s) => s);
  const [activeBlockId, setActiveBlockId] = useState<string | null>(null);
  const [newTagInput, setNewTagInput] = useState('');
  const [showTagForm, setShowTagForm] = useState(false);
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
                  <button
                    onClick={() => removeTag(t)}
                    style={{ background: 'transparent', border: 'none', cursor: 'pointer', marginLeft: 4, color: 'inherit' }}
                  >
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
                    style={{ fontSize: '0.8rem', padding: '1px 6px', borderRadius: '4px', border: '1px solid var(--border-color)' }}
                    autoFocus
                  />
                  <button onClick={addTag} style={{ fontSize: '0.75rem', cursor: 'pointer' }}>Add</button>
                </div>
              ) : (
                <button
                  onClick={() => setShowTagForm(true)}
                  style={{ background: 'transparent', border: '1px dashed var(--border-color)', borderRadius: '12px', padding: '2px 8px', fontSize: '0.75rem', cursor: 'pointer', color: 'var(--text-muted)' }}
                >
                  + Add Tag
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
                <div className="block-handle">
                  <GripVertical size={14} />
                </div>

                <div className="block-content">
                  {block.kind === 'todo' && (
                    <div className={`block-todo ${block.checked ? 'done' : ''}`}>
                      <input
                        type="checkbox"
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
                      placeholder={idx === 0 ? "Type something, or use '# ' for headings, '- ' for lists, '[[' to link..." : ''}
                    />
                  )}

                  {/* Render preview wikilinks when not actively typing */}
                  {!isEditing && block.text.includes('[[') && (
                    <div style={{ fontSize: '0.82rem', marginTop: '2px', color: 'var(--text-muted)' }}>
                      {renderTextWithWikilinks(block.text)}
                    </div>
                  )}
                </div>
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
};
