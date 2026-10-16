import React, { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import {
  ArrowDown,
  ArrowUp,
  Calendar,
  Check,
  ChevronRight,
  Clock,
  Code2,
  Copy,
  FileText,
  GripVertical,
  Hash,
  Heading1,
  Heading2,
  Heading3,
  Heading4,
  Info,
  Lightbulb,
  Link2,
  List,
  ListChecks,
  ListOrdered,
  Minus,
  Plus,
  Quote,
  Trash2,
  Type,
} from 'lucide-react';
import { Block, BlockKind, NotionColor, NotionTextColor, Page, newBlock, todayKey } from '../model';
import { store, useStore } from '../store';
import { buildIndex } from '../links';
import { markdownToPage } from '../markdown';
import { TriggerKind, findTrigger, parseInline } from '../inline';
import { useSync } from '../cloud/sync';

type LucideIcon = typeof Type;

const TEXT_KINDS: BlockKind[] = ['paragraph', 'heading', 'todo', 'bullet', 'numbered', 'quote', 'callout', 'code'];
const LIST_KINDS: BlockKind[] = ['bullet', 'numbered', 'todo'];

const TURN_INTO: { label: string; kind: BlockKind; level?: number; icon: LucideIcon }[] = [
  { label: 'Text', kind: 'paragraph', icon: Type },
  { label: 'Heading 1', kind: 'heading', level: 1, icon: Heading1 },
  { label: 'Heading 2', kind: 'heading', level: 2, icon: Heading2 },
  { label: 'Heading 3', kind: 'heading', level: 3, icon: Heading3 },
  { label: 'Heading 4', kind: 'heading', level: 4, icon: Heading4 },
  { label: 'To-do list', kind: 'todo', icon: ListChecks },
  { label: 'Bulleted list', kind: 'bullet', icon: List },
  { label: 'Numbered list', kind: 'numbered', icon: ListOrdered },
  { label: 'Quote', kind: 'quote', icon: Quote },
  { label: 'Callout', kind: 'callout', icon: Lightbulb },
  { label: 'Code block', kind: 'code', icon: Code2 },
];

interface SlashCtx {
  /** Block text with the `/query` removed. */
  text: string;
  /** Where the `/` was; insertions go here. */
  at: number;
}

interface SlashItem {
  id: string;
  label: string;
  hint: string;
  group: string;
  icon: LucideIcon;
  keywords: string;
  run: (block: Block, ctx: SlashCtx) => void;
}

interface MenuState {
  kind: TriggerKind;
  blockId: string;
  start: number;
  query: string;
  index: number;
}

interface Suggestion {
  key: string;
  label: string;
  hint?: string;
  icon: LucideIcon;
  group?: string;
  pick: () => void;
}

const TEXT_COLORS: NotionTextColor[] = ['gray', 'brown', 'orange', 'yellow', 'green', 'blue', 'purple', 'pink', 'red'];
const cap = (s: string) => s.charAt(0).toUpperCase() + s.slice(1);
const PLACEHOLDER = "Type '/' for commands, '[[' to link a page, '#' to tag";
const HEADING_PLACEHOLDER = ['Heading 1', 'Heading 1', 'Heading 2', 'Heading 3', 'Heading 4', 'Heading 5', 'Heading 6'];

const reducedMotion = () => typeof matchMedia === 'function' && matchMedia('(prefers-reduced-motion: reduce)').matches;

function autosize(el: HTMLTextAreaElement | null) {
  if (!el) return;
  el.style.height = 'auto';
  el.style.height = `${el.scrollHeight}px`;
}

const fmtDate = (ms: number) =>
  new Date(ms).toLocaleString(undefined, { year: 'numeric', month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });

/** Markdown shortcuts typed at the start of a plain text block. */
function shortcut(text: string): { kind: BlockKind; strip: number; extra?: Partial<Block> } | null {
  const h = /^(#{1,6}) /.exec(text);
  if (h) return { kind: 'heading', strip: h[0].length, extra: { level: h[1].length } };
  const todo = /^(- )?\[([ xX]?)\] /.exec(text);
  if (todo) return { kind: 'todo', strip: todo[0].length, extra: { checked: todo[2].toLowerCase() === 'x' } };
  const num = /^1[.)] /.exec(text);
  if (num) return { kind: 'numbered', strip: num[0].length };
  if (/^[-*+] /.test(text)) return { kind: 'bullet', strip: 2 };
  if (text.startsWith('> ')) return { kind: 'quote', strip: 2 };
  if (text.startsWith('!> ')) return { kind: 'callout', strip: 3 };
  if (text.startsWith('```')) return { kind: 'code', strip: 3 };
  if (text === '---') return { kind: 'divider', strip: 3 };
  return null;
}

// ---------- block menu (handle) ----------

const BlockMenu: React.FC<{
  block: Block;
  onTurn: (t: { kind: BlockKind; level?: number }) => void;
  onColor: (c: NotionColor) => void;
  onDuplicate: () => void;
  onMove: (dir: -1 | 1) => void;
  onDelete: () => void;
  onClose: () => void;
}> = ({ block, onTurn, onColor, onDuplicate, onMove, onDelete, onClose }) => {
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
    <button key={key} type="button" role="menuitemradio" aria-checked={current === key} className="menu-item" onClick={() => onColor(key)}>
      <span
        className="swatch"
        style={c === 'default' ? undefined : bg ? { background: `var(--nb-${c.replace('_background', '')})` } : { color: `var(--nc-${c})` }}
        aria-hidden="true"
      >
        A
      </span>
      <span>{label}</span>
      {current === key && <span className="menu-end"><Check size={14} aria-hidden="true" /></span>}
    </button>
  );

  const item = (label: string, Icon: LucideIcon, onClick: () => void, extra?: React.ReactNode, danger = false) => (
    <button type="button" role="menuitem" className="menu-item" onClick={onClick} style={danger ? { color: 'var(--danger)' } : undefined}>
      <Icon size={14} aria-hidden="true" />
      <span>{label}</span>
      {extra}
    </button>
  );
  const chevron = <span className="menu-end"><ChevronRight size={14} aria-hidden="true" /></span>;

  return (
    <div className="popover block-menu" role="menu" aria-label="Block menu" ref={ref} onKeyDown={onKeyDown}>
      {sub === null && (
        <>
          {item('Turn into', Type, () => setSub('turn'), chevron)}
          {item('Color', Lightbulb, () => setSub('color'), chevron)}
          <div className="popover-sep" />
          {item('Duplicate', Copy, onDuplicate)}
          {item('Move up', ArrowUp, () => onMove(-1))}
          {item('Move down', ArrowDown, () => onMove(1))}
          <div className="popover-sep" />
          {item('Delete', Trash2, onDelete, undefined, true)}
        </>
      )}
      {sub === 'turn' && (
        <>
          <div className="popover-label">Turn into</div>
          {TURN_INTO.map((t) => (
            <button key={t.label} type="button" role="menuitem" className="menu-item" onClick={() => onTurn(t)}>
              <t.icon size={14} aria-hidden="true" />
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

// ---------- suggestion popover (/, [[, #) ----------

const SuggestMenu: React.FC<{ items: Suggestion[]; index: number; label: string; onHover: (i: number) => void }> = ({ items, index, label, onHover }) => {
  const listRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    listRef.current?.querySelector<HTMLElement>(`[data-i="${index}"]`)?.scrollIntoView({ block: 'nearest' });
  }, [index]);
  let lastGroup: string | undefined;
  return (
    <div className="popover suggest-menu" role="listbox" aria-label={label} ref={listRef} onMouseDown={(e) => e.preventDefault()}>
      {items.length === 0 && <div className="suggest-empty">No results</div>}
      {items.map((s, i) => {
        const head = s.group && s.group !== lastGroup ? <div className="popover-label">{s.group}</div> : null;
        lastGroup = s.group;
        return (
          <React.Fragment key={s.key}>
            {head}
            <button
              type="button"
              role="option"
              aria-selected={i === index}
              data-i={i}
              id={`suggest-${i}`}
              className={`menu-item suggest-item${i === index ? ' selected' : ''}`}
              onMouseEnter={() => onHover(i)}
              onClick={s.pick}
            >
              <span className="suggest-icon"><s.icon size={16} aria-hidden="true" /></span>
              <span className="suggest-text">
                <span>{s.label}</span>
                {s.hint && <span className="suggest-hint">{s.hint}</span>}
              </span>
            </button>
          </React.Fragment>
        );
      })}
    </div>
  );
};

// ---------- editor ----------

export const BlockEditor: React.FC<{ page: Page }> = ({ page }) => {
  const pages = useStore((s) => s.pages);
  const readOnly = useSync((s) => s.roles[page.id] === 'viewer');
  const [active, setActive] = useState<string | null>(null);
  const [menu, setMenu] = useState<MenuState | null>(null);
  const [menuBlockId, setMenuBlockId] = useState<string | null>(null);
  const [newTagInput, setNewTagInput] = useState('');
  const [showTagForm, setShowTagForm] = useState(false);
  const [infoOpen, setInfoOpen] = useState(() => {
    try {
      return localStorage.getItem('vellune:info-open') !== '0';
    } catch {
      return true;
    }
  });
  const [drag, setDrag] = useState<{ id: string; over?: string; after?: boolean } | null>(null);
  const handleRefs = useRef<Record<string, HTMLButtonElement | null>>({});
  const blockRefs = useRef<Record<string, HTMLTextAreaElement | null>>({});
  const titleRef = useRef<HTMLTextAreaElement>(null);
  const pendingFocus = useRef<{ id: string; caret?: number } | null>(null);

  const index = useMemo(() => buildIndex(Object.values(pages)), [pages]);
  const allTags = useMemo(() => [...new Set([...index.tags.keys(), ...page.tags])].sort(), [index, page.tags]);

  // Place the caret after React has rendered the textarea for the newly active block.
  useLayoutEffect(() => {
    const f = pendingFocus.current;
    const el = f && blockRefs.current[f.id];
    if (!f || !el) return;
    pendingFocus.current = null;
    el.focus({ preventScroll: false });
    const pos = f.caret === undefined ? el.value.length : Math.min(f.caret, el.value.length);
    el.setSelectionRange(pos, pos);
  });
  useLayoutEffect(() => {
    if (active) autosize(blockRefs.current[active]);
    autosize(titleRef.current);
  });

  const focusBlock = (id: string, caret?: number) => {
    if (readOnly) return;
    pendingFocus.current = { id, caret };
    setActive(id);
  };

  const update = (fn: (blocks: Block[]) => Block[]) => store.updatePage(page.id, (p) => ({ ...p, blocks: fn(p.blocks) }));
  const patchBlock = (id: string, patch: (b: Block) => Block) => update((bs) => bs.map((b) => (b.id === id ? patch(b) : b)));
  const insertAfter = (id: string, ...nb: Block[]) =>
    update((bs) => {
      const i = bs.findIndex((b) => b.id === id);
      return [...bs.slice(0, i + 1), ...nb, ...bs.slice(i + 1)];
    });

  // ---------- suggestions ----------

  const replaceRange = (block: Block, start: number, end: number, insert: string, reopen?: TriggerKind) => {
    const text = block.text.slice(0, start) + insert + block.text.slice(end);
    patchBlock(block.id, (b) => ({ ...b, text }));
    focusBlock(block.id, start + insert.length);
    setMenu(reopen ? { kind: reopen, blockId: block.id, start, query: '', index: 0 } : null);
  };

  const slashItems: SlashItem[] = useMemo(() => {
    const turn = (kind: BlockKind, extra: Partial<Block> = {}) => (block: Block, ctx: SlashCtx) => {
      patchBlock(block.id, (b) => ({ ...b, kind, text: ctx.text, level: undefined, checked: undefined, ...extra }));
      focusBlock(block.id, ctx.at);
    };
    const insert = (s: string) => (block: Block, ctx: SlashCtx) => {
      const text = ctx.text.slice(0, ctx.at) + s + ctx.text.slice(ctx.at);
      patchBlock(block.id, (b) => ({ ...b, text }));
      focusBlock(block.id, ctx.at + s.length);
    };
    const T = 'Text';
    const L = 'Lists';
    const B = 'Blocks';
    const R = 'Links & references';
    const D = 'Date & time';
    return [
      { id: 'text', label: 'Text', hint: 'Plain paragraph', group: T, icon: Type, keywords: 'paragraph plain', run: turn('paragraph') },
      { id: 'h1', label: 'Heading 1', hint: '#', group: T, icon: Heading1, keywords: 'title h1 big', run: turn('heading', { level: 1 }) },
      { id: 'h2', label: 'Heading 2', hint: '##', group: T, icon: Heading2, keywords: 'subtitle h2', run: turn('heading', { level: 2 }) },
      { id: 'h3', label: 'Heading 3', hint: '###', group: T, icon: Heading3, keywords: 'h3', run: turn('heading', { level: 3 }) },
      { id: 'h4', label: 'Heading 4', hint: '####', group: T, icon: Heading4, keywords: 'h4', run: turn('heading', { level: 4 }) },
      { id: 'todo', label: 'To-do list', hint: '[]', group: L, icon: ListChecks, keywords: 'todo task checkbox check', run: turn('todo', { checked: false }) },
      { id: 'bullet', label: 'Bulleted list', hint: '-', group: L, icon: List, keywords: 'bullet unordered ul', run: turn('bullet') },
      { id: 'numbered', label: 'Numbered list', hint: '1.', group: L, icon: ListOrdered, keywords: 'number ordered ol', run: turn('numbered') },
      { id: 'quote', label: 'Quote', hint: '>', group: B, icon: Quote, keywords: 'blockquote citation', run: turn('quote') },
      { id: 'callout', label: 'Callout', hint: '!>', group: B, icon: Lightbulb, keywords: 'note info tip warning highlight', run: turn('callout') },
      { id: 'code', label: 'Code block', hint: '```', group: B, icon: Code2, keywords: 'code snippet pre', run: turn('code') },
      {
        id: 'divider',
        label: 'Divider',
        hint: '---',
        group: B,
        icon: Minus,
        keywords: 'hr line separator rule',
        run: (block, ctx) => {
          const next = newBlock();
          if (ctx.text.trim() === '') {
            patchBlock(block.id, (b) => ({ ...b, kind: 'divider', text: '' }));
            insertAfter(block.id, next);
          } else {
            patchBlock(block.id, (b) => ({ ...b, text: ctx.text }));
            insertAfter(block.id, newBlock('divider'), next);
          }
          focusBlock(next.id, 0);
        },
      },
      {
        id: 'link',
        label: 'Link to page',
        hint: '[[',
        group: R,
        icon: Link2,
        keywords: 'wikilink page doc reference mention',
        run: (block, ctx) => {
          const text = `${ctx.text.slice(0, ctx.at)}[[${ctx.text.slice(ctx.at)}`;
          patchBlock(block.id, (b) => ({ ...b, text }));
          focusBlock(block.id, ctx.at + 2);
          setMenu({ kind: 'link', blockId: block.id, start: ctx.at, query: '', index: 0 });
        },
      },
      {
        id: 'subpage',
        label: 'New linked page',
        hint: 'Create and link',
        group: R,
        icon: FileText,
        keywords: 'page doc subpage new create',
        run: (block, ctx) => {
          let n = 1;
          while (index.resolve(n === 1 ? 'Untitled' : `Untitled ${n}`)) n++;
          const title = n === 1 ? 'Untitled' : `Untitled ${n}`;
          store.createPage(title, {}, false);
          insert(`[[${title}]]`)(block, ctx);
        },
      },
      {
        id: 'tag',
        label: 'Tag',
        hint: '#',
        group: R,
        icon: Hash,
        keywords: 'tag label category',
        run: (block, ctx) => {
          const pad = ctx.at > 0 && !/\s/.test(ctx.text[ctx.at - 1]) ? ' ' : '';
          const text = `${ctx.text.slice(0, ctx.at)}${pad}#${ctx.text.slice(ctx.at)}`;
          patchBlock(block.id, (b) => ({ ...b, text }));
          focusBlock(block.id, ctx.at + pad.length + 1);
          setMenu({ kind: 'tag', blockId: block.id, start: ctx.at + pad.length, query: '', index: 0 });
        },
      },
      { id: 'today', label: 'Today', hint: "Link today's journal", group: D, icon: Calendar, keywords: 'date journal daily now', run: (b, c) => insert(`[[${todayKey()}]]`)(b, c) },
      {
        id: 'date',
        label: 'Date',
        hint: new Date().toLocaleDateString(undefined, { year: 'numeric', month: 'long', day: 'numeric' }),
        group: D,
        icon: Calendar,
        keywords: 'date day',
        run: (b, c) => insert(new Date().toLocaleDateString(undefined, { year: 'numeric', month: 'long', day: 'numeric' }))(b, c),
      },
      {
        id: 'time',
        label: 'Time',
        hint: new Date().toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' }),
        group: D,
        icon: Clock,
        keywords: 'time now clock',
        run: (b, c) => insert(new Date().toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' }))(b, c),
      },
    ];
    // Rebuilt per render on purpose: closures read the latest page and index.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [page, index]);

  const menuBlock = menu ? page.blocks.find((b) => b.id === menu.blockId) : undefined;
  const suggestions: Suggestion[] = useMemo(() => {
    if (!menu || !menuBlock) return [];
    const q = menu.query.toLowerCase();
    const end = menu.start + (menu.kind === 'link' ? 2 : 1) + menu.query.length;
    if (menu.kind === 'slash') {
      return slashItems
        .filter((s) => !q || s.label.toLowerCase().includes(q) || s.keywords.includes(q))
        .map((s) => ({
          key: s.id,
          label: s.label,
          hint: s.hint,
          icon: s.icon,
          group: q ? undefined : s.group,
          pick: () => {
            setMenu(null);
            s.run(menuBlock, { text: menuBlock.text.slice(0, menu.start) + menuBlock.text.slice(end), at: menu.start });
          },
        }));
    }
    if (menu.kind === 'link') {
      const hits = Object.values(pages)
        .filter((p) => !p.deletedAt && p.title && p.title.toLowerCase().includes(q))
        .sort((a, b) => Number(b.title.toLowerCase().startsWith(q)) - Number(a.title.toLowerCase().startsWith(q)) || b.updatedAt - a.updatedAt)
        .slice(0, 8);
      // Typing `]]` yourself closes the link; keep any `]]` already right after the caret.
      const closeEnd = menuBlock.text.slice(end, end + 2) === ']]' ? end + 2 : end;
      const items: Suggestion[] = hits.map((p) => ({
        key: p.id,
        label: p.title,
        hint: p.journal ? 'Journal' : p.tags.length ? p.tags.map((t) => `#${t}`).join(' ') : undefined,
        icon: p.journal ? Calendar : FileText,
        pick: () => replaceRange(menuBlock, menu.start, closeEnd, `[[${p.title}]]`),
      }));
      const title = menu.query.trim();
      if (title && !index.resolve(title)) {
        items.push({
          key: 'create',
          label: `Create “${title}”`,
          hint: 'New page',
          icon: Plus,
          pick: () => {
            store.createPage(title, {}, false);
            replaceRange(menuBlock, menu.start, closeEnd, `[[${title}]]`);
          },
        });
      }
      return items;
    }
    // The tag being typed is already in the live index; it only counts if something else uses it.
    const typing = (t: string) => t.toLowerCase() === q && !page.tags.includes(t) && [...(index.tags.get(t) ?? [])].every((id) => id === page.id);
    const known = allTags.filter((t) => !typing(t));
    const tags = known
      .filter((t) => t.toLowerCase().includes(q))
      .sort((a, b) => (index.tags.get(b)?.size ?? 0) - (index.tags.get(a)?.size ?? 0))
      .slice(0, 8);
    const items: Suggestion[] = tags.map((t) => ({
      key: t,
      label: `#${t}`,
      hint: `${index.tags.get(t)?.size ?? 0} pages`,
      icon: Hash,
      pick: () => replaceRange(menuBlock, menu.start, end, `#${t} `),
    }));
    const raw = menu.query.trim();
    if (raw && !/^\d+$/.test(raw) && !known.some((t) => t.toLowerCase() === raw.toLowerCase())) {
      items.push({ key: 'new-tag', label: `Create tag #${raw}`, icon: Plus, pick: () => replaceRange(menuBlock, menu.start, end, `#${raw} `) });
    }
    return items;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [menu, menuBlock, slashItems, pages, allTags, index]);

  // ---------- editing ----------

  const onChange = (block: Block, el: HTMLTextAreaElement) => {
    let text = el.value;
    let caret = el.selectionStart;
    const sc = block.kind === 'paragraph' ? shortcut(text) : null;
    if (sc && caret >= sc.strip) {
      text = sc.kind === 'divider' ? '' : text.slice(sc.strip);
      caret -= sc.strip;
      patchBlock(block.id, (b) => ({ ...b, kind: sc.kind, text, ...sc.extra }));
      if (sc.kind === 'divider') {
        const next = newBlock();
        insertAfter(block.id, next);
        focusBlock(next.id, 0);
      } else {
        pendingFocus.current = { id: block.id, caret };
      }
      setMenu(null);
      return;
    }
    patchBlock(block.id, (b) => ({ ...b, text }));
    autosize(el);
    const trig = block.kind === 'code' ? null : findTrigger(text, caret);
    setMenu(trig ? { ...trig, blockId: block.id, index: menu?.kind === trig.kind && menu.blockId === block.id ? Math.min(menu.index, 50) : 0 } : null);
  };

  const neighbor = (i: number, dir: -1 | 1) => {
    for (let j = i + dir; j >= 0 && j < page.blocks.length; j += dir) if (page.blocks[j].kind !== 'divider') return page.blocks[j];
    return undefined;
  };

  const wrap = (block: Block, el: HTMLTextAreaElement, mark: string) => {
    const { selectionStart: s, selectionEnd: e } = el;
    const t = block.text;
    const wrapped = t.slice(s - mark.length, s) === mark && t.slice(e, e + mark.length) === mark;
    const text = wrapped ? t.slice(0, s - mark.length) + t.slice(s, e) + t.slice(e + mark.length) : t.slice(0, s) + mark + t.slice(s, e) + mark + t.slice(e);
    patchBlock(block.id, (b) => ({ ...b, text }));
    const shift = wrapped ? -mark.length : mark.length;
    requestAnimationFrame(() => el.setSelectionRange(s + shift, e + shift));
  };

  const onKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>, i: number, block: Block) => {
    const el = e.currentTarget;
    const pos = el.selectionStart;
    const collapsed = pos === el.selectionEnd;

    if (menu && menu.blockId === block.id) {
      if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
        e.preventDefault();
        const n = suggestions.length || 1;
        setMenu({ ...menu, index: (menu.index + (e.key === 'ArrowDown' ? 1 : n - 1)) % n });
        return;
      }
      if ((e.key === 'Enter' || e.key === 'Tab') && suggestions.length) {
        e.preventDefault();
        suggestions[Math.min(menu.index, suggestions.length - 1)].pick();
        return;
      }
      if (e.key === 'Escape') {
        e.preventDefault();
        setMenu(null);
        return;
      }
    }

    const mod = e.metaKey || e.ctrlKey;
    if (mod && !e.altKey) {
      const mark = e.key === 'b' ? '**' : e.key === 'i' ? '*' : e.key === 'e' ? '`' : e.shiftKey && e.key.toLowerCase() === 's' ? '~~' : null;
      if (mark && block.kind !== 'code') {
        e.preventDefault();
        wrap(block, el, mark);
        return;
      }
    }

    if (e.key === 'Escape') {
      el.blur();
      return;
    }

    if (e.key === 'Tab' && block.kind === 'code') {
      e.preventDefault();
      const text = `${block.text.slice(0, pos)}  ${block.text.slice(el.selectionEnd)}`;
      patchBlock(block.id, (b) => ({ ...b, text }));
      pendingFocus.current = { id: block.id, caret: pos + 2 };
      return;
    }

    if (e.key === 'Enter' && !e.nativeEvent.isComposing) {
      if (block.kind === 'code' && !mod) return; // newline inside code; Ctrl/Cmd+Enter leaves the block
      if (e.shiftKey) return; // soft line break
      e.preventDefault();
      if (block.text === '' && (LIST_KINDS.includes(block.kind) || block.kind === 'quote' || block.kind === 'callout')) {
        patchBlock(block.id, (b) => ({ ...b, kind: 'paragraph', checked: undefined }));
        return;
      }
      if (pos === 0 && block.text !== '' && collapsed) {
        update((bs) => [...bs.slice(0, i), newBlock(), ...bs.slice(i)]);
        pendingFocus.current = { id: block.id, caret: 0 };
        return;
      }
      const before = block.text.slice(0, pos);
      const after = block.text.slice(el.selectionEnd);
      const kind: BlockKind = LIST_KINDS.includes(block.kind) ? block.kind : 'paragraph';
      const next = newBlock(kind, block.kind === 'code' ? '' : after, kind === 'todo' ? { checked: false } : {});
      patchBlock(block.id, (b) => ({ ...b, text: block.kind === 'code' ? block.text : before }));
      insertAfter(block.id, next);
      focusBlock(next.id, 0);
      setMenu(null);
      return;
    }

    if (e.key === 'Backspace' && pos === 0 && collapsed) {
      if (block.kind !== 'paragraph') {
        e.preventDefault();
        patchBlock(block.id, (b) => ({ ...b, kind: 'paragraph', level: undefined, checked: undefined }));
        return;
      }
      const prev = page.blocks[i - 1];
      if (!prev) return;
      e.preventDefault();
      if (prev.kind === 'divider') {
        update((bs) => bs.filter((b) => b.id !== prev.id));
        pendingFocus.current = { id: block.id, caret: 0 };
        return;
      }
      update((bs) => bs.filter((b) => b.id !== block.id).map((b) => (b.id === prev.id ? { ...b, text: b.text + block.text } : b)));
      focusBlock(prev.id, prev.text.length);
      return;
    }

    if (e.key === 'Delete' && pos === block.text.length && collapsed) {
      const next = page.blocks[i + 1];
      if (!next) return;
      e.preventDefault();
      update((bs) => bs.filter((b) => b.id !== next.id).map((b) => (b.id === block.id ? { ...b, text: b.text + next.text } : b)));
      pendingFocus.current = { id: block.id, caret: pos };
      return;
    }

    if ((e.key === 'ArrowUp' || e.key === 'ArrowLeft') && collapsed && !e.shiftKey) {
      const lineHeight = parseFloat(getComputedStyle(el).lineHeight) || 24;
      const firstLine = !block.text.slice(0, pos).includes('\n') && (el.scrollHeight < lineHeight * 1.8 || pos === 0);
      if ((e.key === 'ArrowUp' && firstLine) || (e.key === 'ArrowLeft' && pos === 0)) {
        const prev = neighbor(i, -1);
        if (prev) {
          e.preventDefault();
          focusBlock(prev.id, e.key === 'ArrowLeft' ? undefined : Math.min(pos, prev.text.length));
        }
      }
      return;
    }
    if ((e.key === 'ArrowDown' || e.key === 'ArrowRight') && collapsed && !e.shiftKey) {
      const lineHeight = parseFloat(getComputedStyle(el).lineHeight) || 24;
      const lastLine = !block.text.slice(pos).includes('\n') && (el.scrollHeight < lineHeight * 1.8 || pos === block.text.length);
      if ((e.key === 'ArrowDown' && lastLine) || (e.key === 'ArrowRight' && pos === block.text.length)) {
        const next = neighbor(i, 1);
        if (next) {
          e.preventDefault();
          focusBlock(next.id, e.key === 'ArrowRight' ? 0 : Math.min(pos, next.text.length));
        }
      }
    }
  };

  const onPaste = (e: React.ClipboardEvent<HTMLTextAreaElement>, block: Block) => {
    const text = e.clipboardData.getData('text/plain');
    if (block.kind === 'code' || !text.includes('\n')) return;
    const parsed = markdownToPage(text, 'paste').blocks;
    if (parsed.length < 2) return;
    e.preventDefault();
    const [first, ...rest] = parsed;
    const el = e.currentTarget;
    const head = block.text.slice(0, el.selectionStart);
    const tail = block.text.slice(el.selectionEnd);
    // Pasted Markdown becomes real blocks; the first one merges into the current block.
    if (head === '' && block.kind === 'paragraph') patchBlock(block.id, (b) => ({ ...b, kind: first.kind, level: first.level, checked: first.checked, language: first.language, text: first.text }));
    else patchBlock(block.id, (b) => ({ ...b, text: head + first.text }));
    const last = rest[rest.length - 1] ?? null;
    if (last && tail) last.text += tail;
    insertAfter(block.id, ...rest);
    if (last) focusBlock(last.id);
  };

  // ---------- rendered (non-editing) text ----------

  const renderRich = (text: string) =>
    parseInline(text).map((t, k) => {
      const s = t.start;
      switch (t.type) {
        case 'bold':
          return <strong key={k} data-s={s}>{t.text}</strong>;
        case 'italic':
          return <em key={k} data-s={s}>{t.text}</em>;
        case 'strike':
          return <s key={k} data-s={s}>{t.text}</s>;
        case 'code':
          return <code key={k} className="inline-code" data-s={s}>{t.text}</code>;
        case 'wikilink': {
          const target = index.resolve(t.target);
          return (
            <a
              key={k}
              data-s={s}
              href="#"
              className={`wikilink-tag${target ? '' : ' unresolved'}`}
              title={target ? `Open ${target.title}` : `Create page “${t.target}”`}
              onClick={(e) => {
                e.preventDefault();
                e.stopPropagation();
                store.openOrCreate(t.target, (x) => index.resolve(x));
              }}
            >
              {t.text}
            </a>
          );
        }
        case 'link':
        case 'url':
          return (
            <a key={k} data-s={s} href={t.href} target="_blank" rel="noopener noreferrer" className="inline-link" onClick={(e) => e.stopPropagation()}>
              {t.text}
            </a>
          );
        case 'tag':
          return (
            <a
              key={k}
              data-s={s}
              href="#"
              className="inline-tag"
              title={`Show pages tagged #${t.tag}`}
              onClick={(e) => {
                e.preventDefault();
                e.stopPropagation();
                store.setDatabaseTag(t.tag);
              }}
            >
              {t.text}
            </a>
          );
        default:
          return <span key={k} data-s={s}>{t.text}</span>;
      }
    });

  /** Click in rendered text: switch the block to editing with the caret where the user clicked. */
  const onRenderedClick = (e: React.MouseEvent, block: Block) => {
    if (readOnly) return;
    const sel = window.getSelection();
    if (sel && !sel.isCollapsed) return; // the user is selecting text to copy
    let caret: number | undefined;
    const node = sel?.anchorNode;
    const host = (node?.nodeType === Node.TEXT_NODE ? node.parentElement : (node as Element | null))?.closest<HTMLElement>('[data-s]');
    if (host && e.currentTarget.contains(host)) caret = Number(host.dataset.s) + (node?.nodeType === Node.TEXT_NODE ? sel!.anchorOffset : 0);
    focusBlock(block.id, caret);
  };

  const editable = (block: Block, i: number) => {
    if (active === block.id && !readOnly) {
      return (
        <textarea
          ref={(el) => {
            blockRefs.current[block.id] = el;
          }}
          className="block-input"
          rows={1}
          value={block.text}
          spellCheck={block.kind !== 'code'}
          aria-label={block.kind === 'heading' ? `Heading ${block.level ?? 1}` : 'Block text'}
          aria-autocomplete="list"
          aria-expanded={menu?.blockId === block.id}
          aria-activedescendant={menu?.blockId === block.id && suggestions.length ? `suggest-${menu.index}` : undefined}
          onChange={(e) => onChange(block, e.currentTarget)}
          onKeyDown={(e) => onKeyDown(e, i, block)}
          onPaste={(e) => onPaste(e, block)}
          onSelect={(e) => {
            const el = e.currentTarget;
            if (menu?.blockId === block.id && (el.selectionStart < menu.start || el.selectionStart !== el.selectionEnd)) setMenu(null);
          }}
          onBlur={() => {
            setActive((a) => (a === block.id ? null : a));
            setMenu(null);
          }}
          placeholder={block.kind === 'heading' ? HEADING_PLACEHOLDER[block.level ?? 1] : block.kind === 'code' ? 'Code' : PLACEHOLDER}
        />
      );
    }
    const lonelyEmpty = page.blocks.length === 1 && block.text === '';
    return (
      <div
        className={`block-text${block.text === '' ? ' empty' : ''}`}
        data-placeholder={lonelyEmpty && !readOnly ? PLACEHOLDER : undefined}
        role={readOnly ? undefined : 'textbox'}
        tabIndex={readOnly ? undefined : 0}
        aria-label={readOnly ? undefined : 'Edit block'}
        onClick={(e) => onRenderedClick(e, block)}
        onKeyDown={(e) => {
          if (e.target === e.currentTarget && (e.key === 'Enter' || e.key === ' ')) {
            e.preventDefault();
            focusBlock(block.id);
          }
        }}
      >
        {block.kind === 'code' ? <span data-s={0}>{block.text}</span> : renderRich(block.text)}
      </div>
    );
  };

  // ---------- block chrome ----------

  const moveBlock = (id: string, to: string, after: boolean) =>
    update((bs) => {
      if (id === to) return bs;
      const moving = bs.find((b) => b.id === id)!;
      const rest = bs.filter((b) => b.id !== id);
      const j = rest.findIndex((b) => b.id === to) + (after ? 1 : 0);
      return [...rest.slice(0, j), moving, ...rest.slice(j)];
    });

  const closeMenu = (refocus = true) => {
    const id = menuBlockId;
    setMenuBlockId(null);
    if (refocus && id) setTimeout(() => handleRefs.current[id]?.focus(), 0);
  };

  const addTag = () => {
    const tag = newTagInput.trim().replace(/^#/, '');
    if (tag && !page.tags.includes(tag)) store.updatePage(page.id, (p) => ({ ...p, tags: [...p.tags, tag] }));
    setNewTagInput('');
    setShowTagForm(false);
  };
  const removeTag = (tag: string) => store.updatePage(page.id, (p) => ({ ...p, tags: p.tags.filter((t) => t !== tag) }));

  const toggleInfo = () => {
    setInfoOpen((o) => {
      try {
        localStorage.setItem('vellune:info-open', o ? '0' : '1');
      } catch {
        /* storage blocked */
      }
      return !o;
    });
  };

  const headings = page.blocks.filter((b) => b.kind === 'heading' && b.text.trim());
  const backlinkCount = index.backlinks.get(page.id)?.length ?? 0;
  const words = page.blocks.reduce((n, b) => n + (b.text.match(/\S+/g)?.length ?? 0), 0);

  let num = 0;
  return (
    <div className="editor-scroll-area">
      {headings.length > 1 && (
        <nav className="doc-outline" aria-label="Outline">
          <div className="doc-outline-bars" aria-hidden="true">
            {headings.map((h) => (
              <span key={h.id} className={`doc-outline-bar l${Math.min(h.level ?? 1, 4)}`} />
            ))}
          </div>
          <div className="doc-outline-panel">
            {headings.map((h) => (
              <button
                key={h.id}
                type="button"
                className={`doc-outline-item l${Math.min(h.level ?? 1, 4)}`}
                onClick={() => document.getElementById(`b-${h.id}`)?.scrollIntoView({ behavior: reducedMotion() ? 'auto' : 'smooth', block: 'start' })}
              >
                {h.text.replace(/\[\[([^\]|]+)(?:\|([^\]]+))?\]\]/g, (_, a, b) => b ?? a)}
              </button>
            ))}
          </div>
        </nav>
      )}
      <div className="editor-center">
        {readOnly && <div className="read-only-badge">View only — the owner shared this page with you as a viewer</div>}
        {/* A disabled fieldset makes every control read-only for viewers in one place. */}
        <fieldset className="editor-fieldset" disabled={readOnly}>
          <textarea
            ref={titleRef}
            className="doc-title-input"
            rows={1}
            value={page.title}
            placeholder="Untitled"
            aria-label="Page title"
            onChange={(e) => {
              const title = e.target.value.replace(/\n/g, ' ');
              store.updatePage(page.id, (p) => ({ ...p, title }));
            }}
            onKeyDown={(e) => {
              if (e.key === 'Enter' || (e.key === 'ArrowDown' && e.currentTarget.selectionStart === page.title.length)) {
                e.preventDefault();
                const first = page.blocks[0];
                if (first && first.kind !== 'divider') focusBlock(first.id, 0);
                else {
                  const b = newBlock();
                  update((bs) => [b, ...bs]);
                  focusBlock(b.id, 0);
                }
              }
            }}
          />

          <section className="doc-info" aria-label="Page info">
            <button type="button" className="doc-info-toggle" aria-expanded={infoOpen} onClick={toggleInfo}>
              <Info size={14} aria-hidden="true" />
              <span>Info</span>
              <ChevronRight size={14} className={`doc-info-caret${infoOpen ? ' open' : ''}`} aria-hidden="true" />
            </button>
            {infoOpen && (
              <div className="page-properties">
                <div className="property-row">
                  <span className="property-label"><Hash size={14} aria-hidden="true" /> Tags</span>
                  <div className="property-tags">
                    {page.tags.map((t) => (
                      <span key={t} className="tag-badge">
                        <button type="button" className="tag-open" onClick={() => store.setDatabaseTag(t)} title={`Show pages tagged #${t}`}>
                          #{t}
                        </button>
                        <button type="button" onClick={() => removeTag(t)} aria-label={`Remove tag ${t}`}>
                          ×
                        </button>
                      </span>
                    ))}
                    {showTagForm ? (
                      <input
                        type="text"
                        value={newTagInput}
                        list="vellune-tag-options"
                        onChange={(e) => setNewTagInput(e.target.value)}
                        placeholder="Type a tag, Enter to add"
                        onKeyDown={(e) => {
                          if (e.key === 'Enter') addTag();
                          if (e.key === 'Escape') setShowTagForm(false);
                        }}
                        onBlur={addTag}
                        className="tag-input"
                        aria-label="New tag"
                        autoFocus
                      />
                    ) : (
                      <button type="button" className="tag-add" onClick={() => setShowTagForm(true)}>
                        <Plus size={12} aria-hidden="true" /> Add tag
                      </button>
                    )}
                    <datalist id="vellune-tag-options">
                      {allTags.filter((t) => !page.tags.includes(t)).map((t) => (
                        <option key={t} value={t} />
                      ))}
                    </datalist>
                  </div>
                </div>
                {page.journal && (
                  <div className="property-row">
                    <span className="property-label"><Calendar size={14} aria-hidden="true" /> Journal</span>
                    <span className="property-value">{new Date(`${page.journal}T00:00`).toLocaleDateString(undefined, { weekday: 'long', year: 'numeric', month: 'long', day: 'numeric' })}</span>
                  </div>
                )}
                <div className="property-row">
                  <span className="property-label"><Clock size={14} aria-hidden="true" /> Created</span>
                  <span className="property-value">{fmtDate(page.createdAt)}</span>
                </div>
                <div className="property-row">
                  <span className="property-label"><Clock size={14} aria-hidden="true" /> Updated</span>
                  <span className="property-value">{fmtDate(page.updatedAt)}</span>
                </div>
                <div className="property-row">
                  <span className="property-label"><Link2 size={14} aria-hidden="true" /> Backlinks</span>
                  <span className="property-value">{backlinkCount} · {words} words</span>
                </div>
              </div>
            )}
          </section>

          <div className="blocks-container" onDragEnd={() => setDrag(null)}>
            {page.blocks.map((block, i) => {
              num = block.kind === 'numbered' ? num + 1 : 0;
              const isDrop = drag && drag.over === block.id && drag.id !== block.id;
              return (
                <div
                  key={block.id}
                  id={`b-${block.id}`}
                  className={`block-row block-${block.kind}${block.kind === 'heading' ? ` block-heading-${block.level ?? 1}` : ''}${active === block.id ? ' editing' : ''}${drag?.id === block.id ? ' dragging' : ''}${isDrop ? (drag.after ? ' drop-after' : ' drop-before') : ''}`}
                  onDragOver={(e) => {
                    if (!drag) return;
                    e.preventDefault();
                    const r = e.currentTarget.getBoundingClientRect();
                    const after = e.clientY > r.top + r.height / 2;
                    if (drag.over !== block.id || drag.after !== after) setDrag({ ...drag, over: block.id, after });
                  }}
                  onDrop={(e) => {
                    e.preventDefault();
                    if (drag?.over) moveBlock(drag.id, drag.over, !!drag.after);
                    setDrag(null);
                  }}
                >
                  <div className={`block-handle-wrap ${menuBlockId === block.id ? 'open' : ''}`}>
                    <button
                      type="button"
                      className="block-handle"
                      aria-label="Drag to move, click for block menu"
                      title="Drag to move · Click for options"
                      aria-haspopup="menu"
                      aria-expanded={menuBlockId === block.id}
                      draggable={!readOnly}
                      onDragStart={(e) => {
                        e.dataTransfer.effectAllowed = 'move';
                        e.dataTransfer.setData('text/plain', block.text);
                        const row = document.getElementById(`b-${block.id}`);
                        if (row) e.dataTransfer.setDragImage(row, 40, 12);
                        setDrag({ id: block.id });
                      }}
                      ref={(el) => {
                        handleRefs.current[block.id] = el;
                      }}
                      onClick={(e) => {
                        e.stopPropagation();
                        setMenuBlockId(menuBlockId === block.id ? null : block.id);
                      }}
                    >
                      <GripVertical size={16} aria-hidden="true" />
                    </button>
                  </div>

                  {menuBlockId === block.id && (
                    <BlockMenu
                      block={block}
                      onClose={() => closeMenu()}
                      onTurn={(t) => {
                        patchBlock(block.id, (b) => ({
                          ...b,
                          kind: t.kind,
                          level: t.kind === 'heading' ? t.level : undefined,
                          checked: t.kind === 'todo' ? (b.checked ?? false) : undefined,
                          text: t.kind === 'divider' ? '' : b.text,
                        }));
                        closeMenu();
                      }}
                      onColor={(c) => {
                        patchBlock(block.id, (b) => ({ ...b, color: c === 'default' ? undefined : c }));
                        closeMenu();
                      }}
                      onDuplicate={() => {
                        insertAfter(block.id, { ...block, id: newBlock().id });
                        closeMenu();
                      }}
                      onMove={(dir) => {
                        const to = page.blocks[i + dir];
                        if (to) moveBlock(block.id, to.id, dir === 1);
                        closeMenu();
                      }}
                      onDelete={() => {
                        setMenuBlockId(null);
                        update((bs) => (bs.length > 1 ? bs.filter((b) => b.id !== block.id) : [newBlock()]));
                      }}
                    />
                  )}

                  <div className={`block-content${block.color ? ` nc-${block.color}` : ''}`}>
                    {block.kind === 'divider' ? (
                      <hr className="block-divider" />
                    ) : (
                      <div className="block-line">
                        {block.kind === 'todo' && (
                          <input
                            type="checkbox"
                            className="block-check"
                            aria-label="Completed"
                            checked={block.checked ?? false}
                            onChange={(e) => patchBlock(block.id, (b) => ({ ...b, checked: e.target.checked }))}
                          />
                        )}
                        {block.kind === 'bullet' && <span className="bullet-dot" aria-hidden="true">•</span>}
                        {block.kind === 'numbered' && <span className="list-num" aria-hidden="true">{num}.</span>}
                        {block.kind === 'callout' && <Lightbulb size={18} className="callout-icon" aria-hidden="true" />}
                        <div className={`block-body${block.kind === 'todo' && block.checked ? ' done' : ''}`}>{editable(block, i)}</div>
                      </div>
                    )}
                    {menu?.blockId === block.id && TEXT_KINDS.includes(block.kind) && (
                      <SuggestMenu
                        items={suggestions}
                        index={Math.min(menu.index, Math.max(0, suggestions.length - 1))}
                        label={menu.kind === 'slash' ? 'Commands' : menu.kind === 'link' ? 'Link to page' : 'Tags'}
                        onHover={(n) => setMenu({ ...menu, index: n })}
                      />
                    )}
                  </div>
                </div>
              );
            })}
            {!readOnly && (
              <div
                className="blocks-tail"
                aria-hidden="true"
                onClick={() => {
                  const last = page.blocks[page.blocks.length - 1];
                  if (last && last.kind === 'paragraph' && last.text === '') return focusBlock(last.id);
                  const b = newBlock();
                  update((bs) => [...bs, b]);
                  focusBlock(b.id, 0);
                }}
              />
            )}
          </div>
        </fieldset>
      </div>
    </div>
  );
};
