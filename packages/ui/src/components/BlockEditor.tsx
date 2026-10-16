import React, { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import {
  ArrowDown,
  ArrowUp,
  Calendar,
  Check,
  ChevronDown,
  ChevronRight,
  ClipboardCheck,
  CornerDownLeft,
  Palette,
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
  ImageIcon,
  Minus,
  Plus,
  Quote,
  LayoutTemplate,
  Table2,
  Smile,
  Bold,
  Italic,
  Strikethrough,
  Code,
  ExternalLink,
  Trash2,
  Type,
} from 'lucide-react';
import { INLINE_MAX, isImage, prepareImage, readDataUrl } from '../image';
import { uploadBlob } from '../cloud/blobs';
import { BlobImage } from './BlobImage';
import { IconPicker } from './PageIcon';
import { TableBlock } from './TableBlock';
import { emptyTable } from '../table';
import { instantiate, isTemplate } from '../templates';
import { Block, BlockKind, NotionColor, NotionTextColor, Page, newBlock, todayKey } from '../model';
import { store, useStore } from '../store';
import { buildIndex } from '../links';
import { markdownToPage } from '../markdown';
import { TriggerKind, findTrigger, mapOffset, parseInline } from '../inline';
import { CursorPos, RemoteCursor, sync, useSync } from '../cloud/sync';
import { CODE_LANGUAGES, canonicalLanguage, highlightCode } from '../highlight';
import { openContextMenu } from './ContextMenu';
import { routePath } from '../route';
import { depth, listNumber, shiftIndent, subtreeEnd, visibleBlocks } from '../outline';

type LucideIcon = typeof Type;

const TEXT_KINDS: BlockKind[] = ['paragraph', 'heading', 'todo', 'bullet', 'numbered', 'toggle', 'quote', 'callout', 'code'];
const LIST_KINDS: BlockKind[] = ['bullet', 'numbered', 'todo', 'toggle'];

const TURN_INTO: { label: string; kind: BlockKind; level?: number; icon: LucideIcon }[] = [
  { label: 'Text', kind: 'paragraph', icon: Type },
  { label: 'Heading 1', kind: 'heading', level: 1, icon: Heading1 },
  { label: 'Heading 2', kind: 'heading', level: 2, icon: Heading2 },
  { label: 'Heading 3', kind: 'heading', level: 3, icon: Heading3 },
  { label: 'Heading 4', kind: 'heading', level: 4, icon: Heading4 },
  { label: 'To-do list', kind: 'todo', icon: ListChecks },
  { label: 'Bulleted list', kind: 'bullet', icon: List },
  { label: 'Numbered list', kind: 'numbered', icon: ListOrdered },
  { label: 'Toggle list', kind: 'toggle', icon: ChevronRight },
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

/** Position of character `pos` inside a textarea, relative to the textarea, via a styled mirror. */
function caretOffset(el: HTMLTextAreaElement, pos: number): { x: number; y: number } {
  const cs = getComputedStyle(el);
  const div = document.createElement('div');
  for (const k of ['fontFamily', 'fontSize', 'fontWeight', 'lineHeight', 'letterSpacing', 'padding', 'border', 'boxSizing', 'whiteSpace', 'overflowWrap', 'wordBreak'] as const)
    div.style[k] = cs[k];
  Object.assign(div.style, { position: 'absolute', visibility: 'hidden', width: `${el.clientWidth}px`, whiteSpace: 'pre-wrap' });
  div.textContent = el.value.slice(0, pos);
  const mark = document.createElement('span');
  mark.textContent = '\u200b';
  div.appendChild(mark);
  document.body.appendChild(div);
  const at = { x: mark.offsetLeft, y: mark.offsetTop };
  div.remove();
  return at;
}

const FORMATS: { mark: string; label: string; icon: LucideIcon; keys: string }[] = [
  { mark: '**', label: 'Bold', icon: Bold, keys: 'Ctrl+B' },
  { mark: '*', label: 'Italic', icon: Italic, keys: 'Ctrl+I' },
  { mark: '~~', label: 'Strikethrough', icon: Strikethrough, keys: 'Ctrl+Shift+S' },
  { mark: '`', label: 'Inline code', icon: Code, keys: 'Ctrl+E' },
];

function autosize(el: HTMLTextAreaElement | null) {
  // Code textareas overlay their highlighted copy and take its height from CSS.
  if (!el || el.classList.contains('code-input')) return;
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
  if (text.startsWith('+ ')) return { kind: 'toggle', strip: 2 };
  if (/^[-*] /.test(text)) return { kind: 'bullet', strip: 2 };
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
    listRef.current?.scrollIntoView({ block: 'nearest' });
  }, []);
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

// ---------- collaborator cursors ----------

type Caret = RemoteCursor & CursorPos;

const CaretMark: React.FC<{ c: Caret }> = ({ c }) => (
  <span className="remote-caret" style={{ '--caret': c.color } as React.CSSProperties} aria-hidden="true">
    <span className="remote-caret-label">{c.name}</span>
  </span>
);

/** Transparent copy of the source text with carets and selections, laid under a textarea or code. */
const CaretLayer: React.FC<{ text: string; carets: Caret[]; className: string }> = ({ text, carets, className }) => {
  const clamp = (n: number) => Math.max(0, Math.min(text.length, n));
  const pts = [...new Set([0, text.length, ...carets.flatMap((c) => [clamp(c.anchor), clamp(c.head)])])].sort((a, b) => a - b);
  const out: React.ReactNode[] = [];
  pts.forEach((a, i) => {
    carets.filter((c) => clamp(c.head) === a).forEach((c) => out.push(<CaretMark key={`c${c.conn}`} c={c} />));
    const b = pts[i + 1];
    if (b === undefined || b === a) return;
    const sel = carets.find((c) => Math.min(c.anchor, c.head) <= a && b <= Math.max(c.anchor, c.head) && c.anchor !== c.head);
    const seg = text.slice(a, b);
    out.push(sel ? <mark key={`s${a}`} className="remote-sel" style={{ '--caret': sel.color } as React.CSSProperties}>{seg}</mark> : <span key={`t${a}`}>{seg}</span>);
  });
  return <div className={`caret-layer ${className}`} aria-hidden="true">{out}{'\n'}</div>;
};

// ---------- code language picker ----------

const LangPicker: React.FC<{ value?: string; onChange: (lang: string | undefined) => void }> = ({ value, onChange }) => {
  const [open, setOpen] = useState(false);
  const [q, setQ] = useState('');
  const [idx, setIdx] = useState(0);
  const ref = useRef<HTMLDivElement>(null);
  const current = canonicalLanguage(value);
  const label = CODE_LANGUAGES.find(([id]) => id === current)?.[1] ?? value ?? 'Plain text';
  const items = CODE_LANGUAGES.filter(([id, l]) => !q || l.toLowerCase().includes(q.toLowerCase()) || id.includes(q.toLowerCase()));

  useEffect(() => {
    if (!open) return;
    ref.current?.querySelector('.lang-menu')?.scrollIntoView({ block: 'nearest' });
    const onDown = (e: MouseEvent) => ref.current && !ref.current.contains(e.target as Node) && setOpen(false);
    document.addEventListener('mousedown', onDown);
    return () => document.removeEventListener('mousedown', onDown);
  }, [open]);

  const pick = (id: string) => {
    onChange(id || undefined);
    setOpen(false);
    setQ('');
  };

  return (
    <div className="code-lang-wrap" ref={ref}>
      <button
        type="button"
        className="code-lang"
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-label={`Code language: ${label}`}
        onClick={() => {
          setOpen((o) => !o);
          setIdx(Math.max(0, CODE_LANGUAGES.findIndex(([id]) => id === current)));
        }}
      >
        <span>{label}</span>
        <ChevronDown size={12} aria-hidden="true" />
      </button>
      {open && (
        <div className="popover lang-menu" role="listbox" aria-label="Code language">
          <input
            className="lang-search"
            autoFocus
            placeholder="Search language"
            aria-label="Search language"
            value={q}
            onChange={(e) => {
              setQ(e.target.value);
              setIdx(0);
            }}
            onKeyDown={(e) => {
              if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
                e.preventDefault();
                const n = items.length || 1;
                setIdx((i) => (i + (e.key === 'ArrowDown' ? 1 : n - 1)) % n);
              } else if (e.key === 'Enter' && items[idx]) {
                e.preventDefault();
                pick(items[idx][0]);
              } else if (e.key === 'Escape') {
                e.stopPropagation();
                setOpen(false);
              }
            }}
          />
          <div className="lang-list">
            {items.map(([id, l], i) => (
              <button
                key={id || 'plain'}
                type="button"
                role="option"
                aria-selected={id === current}
                className={`menu-item${i === idx ? ' selected' : ''}`}
                onMouseEnter={() => setIdx(i)}
                onClick={() => pick(id)}
              >
                <span>{l}</span>
                {id === current && <span className="menu-end"><Check size={14} aria-hidden="true" /></span>}
              </button>
            ))}
            {!items.length && <div className="suggest-empty">No match</div>}
          </div>
        </div>
      )}
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
  const [copied, setCopied] = useState<string | null>(null);
  const [iconOpen, setIconOpen] = useState(false);
  /** Formatting bar over a text selection in the block being edited. */
  const [selBar, setSelBar] = useState<{ blockId: string; x: number; y: number } | null>(null);
  // Folded toggles are a per-device view choice, like a collapsed sidebar folder; never synced.
  const [collapsedIds, setCollapsed] = useState<Set<string>>(() => {
    try {
      return new Set(JSON.parse(localStorage.getItem('vellune:collapsed') ?? '[]'));
    } catch {
      return new Set();
    }
  });
  const toggleFold = (id: string) => {
    setActive(null); // the block being edited may be about to hide
    setCollapsed((c) => {
      const next = new Set(c);
      if (!next.delete(id)) next.add(id);
      try {
        localStorage.setItem('vellune:collapsed', JSON.stringify([...next].slice(-500)));
      } catch {
        /* storage blocked */
      }
      return next;
    });
  };
  const closeIcon = useCallback(() => setIconOpen(false), []);
  const [drag, setDrag] = useState<{ id: string; over?: string; after?: boolean } | null>(null);
  const handleRefs = useRef<Record<string, HTMLButtonElement | null>>({});
  const blockRefs = useRef<Record<string, HTMLTextAreaElement | null>>({});
  const titleRef = useRef<HTMLTextAreaElement>(null);
  const pendingFocus = useRef<{ id: string; caret?: number } | null>(null);
  const fileInput = useRef<HTMLInputElement>(null);
  const pickTarget = useRef<string | null>(null);

  const index = useMemo(() => buildIndex(Object.values(pages)), [pages]);
  const rawCursors = useSync((s) => s.cursors[page.id]);
  // Re-resolved on every page change so carets follow concurrent edits.
  const remote = useMemo(() => sync.resolveCursors(page.id, rawCursors), [page, rawCursors]);
  const caretsIn = (id: string) => remote.filter((c) => c.blockId === id);
  /** Text and selection of the block being edited as this tab last saw them; detects remote edits. */
  const seen = useRef<{ id: string; text: string; sel: [number, number] } | null>(null);
  const report = (blockId: string, el: HTMLTextAreaElement) => {
    seen.current = { id: blockId, text: el.value, sel: [el.selectionStart, el.selectionEnd] };
    sync.setCursor(page.id, { blockId, anchor: el.selectionStart, head: el.selectionEnd });
  };
  useEffect(() => () => sync.setCursor(page.id, null), [page.id]);

  // Block links (/doc/{id}#b-{block}) scroll to the block and flash it once.
  useEffect(() => {
    const id = location.hash.startsWith('#b-') ? location.hash.slice(1) : null;
    const el = id && document.getElementById(id);
    if (!el) return;
    el.scrollIntoView({ block: 'center', behavior: reducedMotion() ? 'auto' : 'smooth' });
    el.classList.add('flash');
    setTimeout(() => el.classList.remove('flash'), 1600);
  }, [page.id]);
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
    seen.current = { id: f.id, text: el.value, sel: [pos, pos] };
  });
  // A collaborator changed the block being edited: React rewrote the textarea value and moved the
  // caret to the end, so put it back on the same character.
  useLayoutEffect(() => {
    const el = active ? blockRefs.current[active] : null;
    const was = seen.current;
    if (!el || !was || was.id !== active || pendingFocus.current || was.text === el.value) return;
    const [a, b] = was.sel.map((i) => mapOffset(was.text, el.value, i));
    el.setSelectionRange(a, b);
    seen.current = { id: was.id, text: el.value, sel: [a, b] };
  });
  useLayoutEffect(() => {
    if (active) autosize(blockRefs.current[active]);
    autosize(titleRef.current);
  });
  // A fresh, empty page starts in its title; otherwise the "New doc" button keeps focus and
  // the next Space or Enter makes another page.
  useEffect(() => {
    const fresh = Date.now() - page.createdAt < 3000 && page.blocks.every((b) => !b.text);
    if (!readOnly && fresh) {
      titleRef.current?.focus();
      titleRef.current?.select();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [page.id]);

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

  /** Insert images after `anchorId` (default: end of page); an empty paragraph there is replaced. */
  const addImages = async (files: File[], anchorId?: string) => {
    if (readOnly) return;
    const imgs = files.filter(isImage);
    // Encrypted upload when the page is in the cloud; otherwise inline, so offline still works.
    const toSrc = async (f: File) => {
      const data = await prepareImage(f);
      if (!data) return null;
      return (await uploadBlob(page.id, data)) ?? (data.size <= INLINE_MAX ? readDataUrl(data) : null);
    };
    const urls = await Promise.all(imgs.map(toSrc));
    const blocks = urls.flatMap((src, i) => (src ? [newBlock('image', imgs[i].name.replace(/\.[^.]+$/, ''), { src })] : []));
    if (urls.some((u) => !u)) window.alert('Some images were skipped: too large, or too large to keep offline (1.5 MB) until this page syncs.');
    if (!blocks.length) return;
    update((bs) => {
      const at = anchorId ? bs.findIndex((b) => b.id === anchorId) : bs.length - 1;
      const anchor = bs[at];
      const replace = anchor?.kind === 'paragraph' && anchor.text === '';
      const out = [...bs.slice(0, replace ? at : at + 1), ...blocks, ...bs.slice(at + 1)];
      return out[out.length - 1].kind === 'image' ? [...out, newBlock()] : out;
    });
  };

  const startBlockDrag = (e: React.DragEvent, block: Block) => {
    e.dataTransfer.effectAllowed = 'move';
    e.dataTransfer.setData('text/plain', block.text);
    const row = document.getElementById(`b-${block.id}`);
    if (row) e.dataTransfer.setDragImage(row, 40, 12);
    setDrag({ id: block.id });
  };

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
      { id: 'toggle', label: 'Toggle list', hint: '+', group: L, icon: ChevronRight, keywords: 'toggle collapse fold details expand', run: turn('toggle') },
      { id: 'quote', label: 'Quote', hint: '>', group: B, icon: Quote, keywords: 'blockquote citation', run: turn('quote') },
      { id: 'callout', label: 'Callout', hint: '!>', group: B, icon: Lightbulb, keywords: 'note info tip warning highlight', run: turn('callout') },
      { id: 'code', label: 'Code block', hint: '```', group: B, icon: Code2, keywords: 'code snippet pre', run: turn('code') },
      {
        id: 'image',
        label: 'Image',
        hint: 'Upload or paste',
        group: B,
        icon: ImageIcon,
        keywords: 'image picture photo upload img',
        run: (block, ctx) => {
          patchBlock(block.id, (b) => ({ ...b, text: ctx.text }));
          pickTarget.current = block.id;
          fileInput.current?.click();
        },
      },
      {
        id: 'table',
        label: 'Table',
        hint: 'Rows and columns',
        group: B,
        icon: Table2,
        keywords: 'table grid spreadsheet rows columns',
        run: (block, ctx) => {
          const table = newBlock('table', emptyTable(3, 3));
          const after = newBlock();
          if (ctx.text.trim() === '') update((bs) => bs.flatMap((b) => (b.id === block.id ? [{ ...table, id: block.id }, after] : [b])));
          else {
            patchBlock(block.id, (b) => ({ ...b, text: ctx.text }));
            insertAfter(block.id, table, after);
          }
          requestAnimationFrame(() => document.querySelector<HTMLInputElement>(`#b-${ctx.text.trim() ? table.id : block.id} [data-cell="0:0"]`)?.focus());
        },
      },
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
      ...Object.values(pages)
        .filter((t) => isTemplate(t) && t.id !== page.id)
        .sort((a, b) => a.title.localeCompare(b.title))
        .map(
          (t): SlashItem => ({
            id: `tpl-${t.id}`,
            label: t.title || 'Untitled template',
            hint: 'Template',
            group: 'Templates',
            icon: LayoutTemplate,
            keywords: `template ${t.title.toLowerCase()}`,
            run: (block, ctx) => {
              const blocks = instantiate(t, page.title);
              if (!blocks.length) return;
              // An empty block is replaced; otherwise the template goes below it.
              update((bs) => {
                const at = bs.findIndex((b) => b.id === block.id);
                const keep = ctx.text.trim() ? [{ ...bs[at], text: ctx.text }] : [];
                return [...bs.slice(0, at), ...keep, ...blocks, ...bs.slice(at + 1)];
              });
              focusBlock(blocks[blocks.length - 1].id);
            },
          }),
        ),
    ];
    // Rebuilt per render on purpose: closures read the latest page and index.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [page, index, pages]);

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

  const shown = useMemo(() => visibleBlocks(page.blocks, collapsedIds), [page.blocks, collapsedIds]);
  const indexOf = useMemo(() => new Map(page.blocks.map((b, i) => [b.id, i])), [page.blocks]);
  /** Nearest visible text block above (-1) or below (1) block `i`, skipping folded children. */
  const neighbor = (i: number, dir: -1 | 1) => {
    const at = shown.findIndex((b) => b.id === page.blocks[i].id);
    for (let j = at + dir; j >= 0 && j < shown.length; j += dir) if (!['divider', 'image', 'table'].includes(shown[j].kind)) return shown[j];
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

  /** Turn the selection into `[[page]]`, or `[text](https://)` with the URL selected for typing. */
  const linkSelection = (block: Block, as: 'page' | 'url') => {
    const el = blockRefs.current[block.id];
    if (!el) return;
    const { selectionStart: s, selectionEnd: e } = el;
    const sel = block.text.slice(s, e);
    const ins = as === 'page' ? `[[${sel}]]` : `[${sel}](https://)`;
    patchBlock(block.id, (b) => ({ ...b, text: b.text.slice(0, s) + ins + b.text.slice(e) }));
    const [a, z] = as === 'page' ? [s + 2, s + 2 + sel.length] : [s + sel.length + 3, s + ins.length - 1];
    requestAnimationFrame(() => {
      el.focus();
      el.setSelectionRange(a, z);
    });
    setSelBar(null);
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

    if (e.key === 'Tab' && block.kind !== 'code') {
      e.preventDefault();
      update((bs) => shiftIndent(bs, bs.findIndex((b) => b.id === block.id), e.shiftKey ? -1 : 1));
      pendingFocus.current = { id: block.id, caret: pos };
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
        // An empty list item steps out one level first, then leaves the list.
        if (depth(block) > 0 && LIST_KINDS.includes(block.kind)) update((bs) => shiftIndent(bs, i, -1));
        else patchBlock(block.id, (b) => ({ ...b, kind: 'paragraph', checked: undefined }));
        pendingFocus.current = { id: block.id, caret: 0 };
        return;
      }
      if (pos === 0 && block.text !== '' && collapsed) {
        update((bs) => [...bs.slice(0, i), newBlock('paragraph', '', block.indent ? { indent: block.indent } : {}), ...bs.slice(i)]);
        pendingFocus.current = { id: block.id, caret: 0 };
        return;
      }
      const before = block.text.slice(0, pos);
      const after = block.text.slice(el.selectionEnd);
      // An open toggle's Enter writes its first child; a folded one adds a sibling after its subtree.
      const intoToggle = block.kind === 'toggle' && !collapsedIds.has(block.id);
      const kind: BlockKind = intoToggle ? 'paragraph' : LIST_KINDS.includes(block.kind) ? block.kind : 'paragraph';
      const indent = depth(block) + (intoToggle ? 1 : 0);
      const next = newBlock(kind, block.kind === 'code' ? '' : after, { ...(kind === 'todo' ? { checked: false } : {}), ...(indent ? { indent } : {}) });
      update((bs) => {
        const at = bs.findIndex((b) => b.id === block.id);
        const to = block.kind === 'toggle' && !intoToggle ? subtreeEnd(bs, at) : at + 1;
        const cur = { ...bs[at], text: block.kind === 'code' ? block.text : before };
        return [...bs.slice(0, at), cur, ...bs.slice(at + 1, to), next, ...bs.slice(to)];
      });
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
      if (depth(block) > 0) {
        e.preventDefault();
        update((bs) => shiftIndent(bs, i, -1));
        pendingFocus.current = { id: block.id, caret: 0 };
        return;
      }
      const prev = shown[shown.findIndex((b) => b.id === block.id) - 1];
      if (!prev) return;
      e.preventDefault();
      if (prev.kind === 'divider' || prev.kind === 'image') {
        update((bs) => bs.filter((b) => b.id !== prev.id));
        pendingFocus.current = { id: block.id, caret: 0 };
        return;
      }
      if (prev.kind === 'table') {
        // Text never merges into a table; an empty block after one just goes away.
        if (!block.text) update((bs) => bs.filter((b) => b.id !== block.id));
        return;
      }
      update((bs) => bs.filter((b) => b.id !== block.id).map((b) => (b.id === prev.id ? { ...b, text: b.text + block.text } : b)));
      focusBlock(prev.id, prev.text.length);
      return;
    }

    if (e.key === 'Delete' && pos === block.text.length && collapsed) {
      const next = page.blocks[i + 1];
      if (!next || next.kind === 'table') return;
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
    const pasted = [...e.clipboardData.files].filter(isImage);
    if (pasted.length) {
      e.preventDefault();
      void addImages(pasted, block.id);
      return;
    }
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

  const renderRich = (text: string, carets: Caret[] = []) => {
    const toks = parseInline(text);
    if (!toks.length) return carets.map((c) => <CaretMark key={c.conn} c={c} />);
    // Each caret goes into the token whose visible text contains (or ends at) its offset.
    const owner = (c: Caret) => {
      const i = toks.findIndex((t) => c.head <= t.start + t.text.length);
      return i < 0 ? toks.length - 1 : i;
    };
    const split = (t: (typeof toks)[number], k: number): React.ReactNode => {
      const mine = carets.filter((c) => owner(c) === k);
      if (!mine.length) return t.text;
      const cuts = [...new Set(mine.map((c) => Math.max(0, Math.min(t.text.length, c.head - t.start))))].sort((a, b) => a - b);
      const parts: React.ReactNode[] = [];
      let prev = 0;
      for (const cut of cuts) {
        if (cut > prev) parts.push(<span key={`p${prev}`} data-s={t.start + prev}>{t.text.slice(prev, cut)}</span>);
        mine.filter((c) => Math.max(0, Math.min(t.text.length, c.head - t.start)) === cut).forEach((c) => parts.push(<CaretMark key={`c${c.conn}`} c={c} />));
        prev = cut;
      }
      if (prev < t.text.length) parts.push(<span key={`p${prev}`} data-s={t.start + prev}>{t.text.slice(prev)}</span>);
      return parts;
    };
    return toks.map((t, k) => {
      const s = t.start;
      const body = split(t, k);
      switch (t.type) {
        case 'bold':
          return <strong key={k} data-s={s}>{body}</strong>;
        case 'italic':
          return <em key={k} data-s={s}>{body}</em>;
        case 'strike':
          return <s key={k} data-s={s}>{body}</s>;
        case 'code':
          return <code key={k} className="inline-code" data-s={s}>{body}</code>;
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
              {body}
            </a>
          );
        }
        case 'link':
        case 'url':
          return (
            <a key={k} data-s={s} href={t.href} target="_blank" rel="noopener noreferrer" className="inline-link" onClick={(e) => e.stopPropagation()}>
              {body}
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
              {body}
            </a>
          );
        default:
          return <span key={k} data-s={s}>{body}</span>;
      }
    });
  };

  /** Click in rendered text: switch the block to editing with the caret where the user clicked. */
  const onRenderedClick = (e: React.MouseEvent, block: Block) => {
    if (readOnly) return;
    const sel = window.getSelection();
    if (sel && !sel.isCollapsed) return; // the user is selecting text to copy
    let caret: number | undefined;
    const node = sel?.anchorNode;
    if (block.kind === 'code' && node && e.currentTarget.contains(node)) {
      // Highlighted text equals the source, so the text length before the click point is the caret.
      const r = document.createRange();
      r.setStart(e.currentTarget, 0);
      r.setEnd(node, sel!.anchorOffset);
      return focusBlock(block.id, r.toString().length);
    }
    const host = (node?.nodeType === Node.TEXT_NODE ? node.parentElement : (node as Element | null))?.closest<HTMLElement>('[data-s]');
    if (host && e.currentTarget.contains(host)) caret = Number(host.dataset.s) + (node?.nodeType === Node.TEXT_NODE ? sel!.anchorOffset : 0);
    focusBlock(block.id, caret);
  };

  const editable = (block: Block, i: number) => {
    const isCode = block.kind === 'code';
    const codeHtml = isCode ? { __html: `${highlightCode(block.text, block.language)}\n` } : undefined;
    if (active === block.id && !readOnly) {
      const input = (
        <textarea
          ref={(el) => {
            blockRefs.current[block.id] = el;
          }}
          className={isCode ? 'block-input code-input' : 'block-input'}
          rows={1}
          value={block.text}
          spellCheck={!isCode}
          aria-label={block.kind === 'heading' ? `Heading ${block.level ?? 1}` : 'Block text'}
          aria-autocomplete="list"
          aria-expanded={menu?.blockId === block.id}
          aria-activedescendant={menu?.blockId === block.id && suggestions.length ? `suggest-${menu.index}` : undefined}
          onChange={(e) => {
            onChange(block, e.currentTarget);
            report(block.id, e.currentTarget);
          }}
          onFocus={(e) => report(block.id, e.currentTarget)}
          onKeyDown={(e) => onKeyDown(e, i, block)}
          onPaste={(e) => onPaste(e, block)}
          onSelect={(e) => {
            const el = e.currentTarget;
            report(block.id, el);
            if (menu?.blockId === block.id && (el.selectionStart < menu.start || el.selectionStart !== el.selectionEnd)) setMenu(null);
            if (el.selectionStart !== el.selectionEnd && !isCode) {
              const a = caretOffset(el, el.selectionStart);
              setSelBar({ blockId: block.id, x: el.offsetLeft + a.x, y: el.offsetTop + a.y });
            } else if (selBar) setSelBar(null);
          }}
          onBlur={() => {
            setSelBar(null);
            sync.setCursor(page.id, null);
            setActive((a) => (a === block.id ? null : a));
            setMenu(null);
          }}
          placeholder={block.kind === 'heading' ? HEADING_PLACEHOLDER[block.level ?? 1] : isCode ? 'Code' : PLACEHOLDER}
        />
      );
      const carets = caretsIn(block.id);
      if (!isCode) {
        return carets.length ? (
          <>
            <CaretLayer text={block.text} carets={carets} className="block-text" />
            {input}
          </>
        ) : input;
      }
      return (
        <div className="code-surface">
          <pre className="code-highlight" aria-hidden="true" dangerouslySetInnerHTML={codeHtml} />
          {carets.length > 0 && <CaretLayer text={block.text} carets={carets} className="code-highlight" />}
          {input}
        </div>
      );
    }
    const lonelyEmpty = page.blocks.length === 1 && block.text === '';
    if (isCode) {
      return (
        <div className="code-surface">
          <pre
            className="code-highlight block-text"
            role={readOnly ? undefined : 'textbox'}
            tabIndex={readOnly ? undefined : 0}
            aria-label={readOnly ? 'Code' : 'Edit code'}
            onClick={(e) => onRenderedClick(e, block)}
            onKeyDown={(e) => {
              if (e.target === e.currentTarget && e.key === 'Enter') {
                e.preventDefault();
                focusBlock(block.id);
              }
            }}
            dangerouslySetInnerHTML={codeHtml}
          />
          {caretsIn(block.id).length > 0 && <CaretLayer text={block.text} carets={caretsIn(block.id)} className="code-highlight" />}
        </div>
      );
    }
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
        {renderRich(block.text, caretsIn(block.id))}
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
          <div className={`doc-icon-row${page.icon ? ' has-icon' : ''}`}>
            {page.icon ? (
              <button type="button" className="doc-icon" aria-label="Change page icon" title="Change icon" onClick={() => setIconOpen((o) => !o)}>
                {page.icon}
              </button>
            ) : (
              !readOnly && (
                <button type="button" className="doc-add-icon" onClick={() => setIconOpen((o) => !o)}>
                  <Smile size={16} aria-hidden="true" /> Add icon
                </button>
              )
            )}
            {iconOpen && !readOnly && (
              <IconPicker current={page.icon} onPick={(icon) => store.updatePage(page.id, (p) => ({ ...p, icon }))} onClose={closeIcon} />
            )}
          </div>
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
                if (first && first.kind !== 'divider' && first.kind !== 'image') focusBlock(first.id, 0);
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

          <input
            ref={fileInput}
            type="file"
            accept="image/*"
            multiple
            hidden
            onChange={(e) => {
              void addImages([...(e.target.files ?? [])], pickTarget.current ?? undefined);
              e.target.value = '';
            }}
          />
          <div
            className="blocks-container"
            onDragEnd={() => setDrag(null)}
            onDragOver={(e) => e.dataTransfer.types.includes('Files') && e.preventDefault()}
            onDrop={(e) => {
              // A dragged <img> can carry its picture as a File; block moves are not uploads.
              if (drag || !e.dataTransfer.files.length) return;
              e.preventDefault();
              void addImages([...e.dataTransfer.files]);
            }}
          >
            {shown.map((block) => {
              const i = indexOf.get(block.id)!;
              const num = block.kind === 'numbered' ? listNumber(page.blocks, i) : 0;
              const hasKids = subtreeEnd(page.blocks, i) > i + 1;
              const folded = collapsedIds.has(block.id);
              const isDrop = drag && drag.over === block.id && drag.id !== block.id;
              return (
                <div
                  key={block.id}
                  id={`b-${block.id}`}
                  onContextMenu={(e) => {
                    if ((e.target as HTMLElement).closest('textarea, input')) return; // keep spellcheck/paste
                    const at = (dir: -1 | 1) => page.blocks[i + dir];
                    openContextMenu(e, [
                      { label: 'Copy text', icon: Copy, onSelect: () => void navigator.clipboard?.writeText(block.text) },
                      {
                        label: 'Copy link to block',
                        icon: Link2,
                        onSelect: () => void navigator.clipboard?.writeText(`${location.origin}${routePath({ view: 'page', pageId: page.id })}#b-${block.id}`),
                      },
                      'separator',
                      ...(readOnly
                        ? []
                        : ([
                            { label: 'Edit', icon: Type, onSelect: () => focusBlock(block.id), disabled: ['divider', 'image', 'table'].includes(block.kind) },
                            {
                              label: 'Insert block below',
                              icon: CornerDownLeft,
                              onSelect: () => {
                                const b = newBlock();
                                insertAfter(block.id, b);
                                focusBlock(b.id, 0);
                              },
                            },
                            { label: 'Turn into / color…', icon: Palette, onSelect: () => setMenuBlockId(block.id) },
                            { label: 'Duplicate', icon: Copy, onSelect: () => insertAfter(block.id, { ...block, id: newBlock().id }) },
                            { label: 'Move up', icon: ArrowUp, disabled: !at(-1), onSelect: () => moveBlock(block.id, at(-1)!.id, false) },
                            { label: 'Move down', icon: ArrowDown, disabled: !at(1), onSelect: () => moveBlock(block.id, at(1)!.id, true) },
                            'separator',
                            {
                              label: 'Delete',
                              icon: Trash2,
                              danger: true,
                              onSelect: () => update((bs) => (bs.length > 1 ? bs.filter((b) => b.id !== block.id) : [newBlock()])),
                            },
                          ] as const)),
                    ]);
                  }}
                  style={block.indent ? { paddingLeft: block.indent * 24 } : undefined}
                  className={`block-row block-${block.kind}${block.kind === 'heading' ? ` block-heading-${block.level ?? 1}` : ''}${active === block.id ? ' editing' : ''}${drag?.id === block.id ? ' dragging' : ''}${isDrop ? (drag.after ? ' drop-after' : ' drop-before') : ''}`}
                  onDragOver={(e) => {
                    if (!drag) return;
                    e.preventDefault();
                    const r = e.currentTarget.getBoundingClientRect();
                    const after = e.clientY > r.top + r.height / 2;
                    if (drag.over !== block.id || drag.after !== after) setDrag({ ...drag, over: block.id, after });
                  }}
                  onDrop={(e) => {
                    if (!drag && e.dataTransfer.files.length) {
                      e.preventDefault();
                      e.stopPropagation();
                      void addImages([...e.dataTransfer.files], block.id);
                      return;
                    }
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
                      onDragStart={(e) => startBlockDrag(e, block)}
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
                    {block.kind === 'code' && (
                      <div className="code-toolbar">
                        <LangPicker value={block.language} onChange={(language) => patchBlock(block.id, (b) => ({ ...b, language }))} />
                        <button
                          type="button"
                          className="code-copy"
                          aria-label="Copy code"
                          title="Copy code"
                          onClick={() => {
                            void navigator.clipboard?.writeText(block.text).then(() => {
                              setCopied(block.id);
                              setTimeout(() => setCopied((c) => (c === block.id ? null : c)), 1500);
                            });
                          }}
                        >
                          {copied === block.id ? <ClipboardCheck size={14} aria-hidden="true" /> : <Copy size={14} aria-hidden="true" />}
                          <span>{copied === block.id ? 'Copied' : 'Copy'}</span>
                        </button>
                      </div>
                    )}
                    {selBar?.blockId === block.id && active === block.id && (
                      <div
                        className="format-bar"
                        role="toolbar"
                        aria-label="Format selection"
                        style={{ left: Math.max(0, selBar.x - 8), top: selBar.y }}
                        // Keep focus and selection in the textarea while clicking.
                        onMouseDown={(e) => e.preventDefault()}
                      >
                        {FORMATS.map((f) => (
                          <button
                            key={f.mark}
                            type="button"
                            className="format-btn"
                            title={`${f.label} (${f.keys})`}
                            aria-label={f.label}
                            onClick={() => {
                              const el = blockRefs.current[block.id];
                              if (el) wrap(block, el, f.mark);
                            }}
                          >
                            <f.icon size={15} aria-hidden="true" />
                          </button>
                        ))}
                        <span className="format-sep" aria-hidden="true" />
                        <button type="button" className="format-btn" title="Link to page ([[ ]])" aria-label="Link to page" onClick={() => linkSelection(block, 'page')}>
                          <Link2 size={15} aria-hidden="true" />
                        </button>
                        <button type="button" className="format-btn" title="Web link" aria-label="Web link" onClick={() => linkSelection(block, 'url')}>
                          <ExternalLink size={15} aria-hidden="true" />
                        </button>
                      </div>
                    )}
                    {block.kind === 'image' ? (
                      block.src && (
                        // The picture itself is a drag handle too: grabbing an image moves its block.
                        <BlobImage
                          pageId={page.id}
                          src={block.src}
                          alt={block.text}
                          className="block-image"
                          draggable={!readOnly}
                          onDragStart={(e) => startBlockDrag(e, block)}
                        />
                      )
                    ) : block.kind === 'divider' ? (
                      <hr className="block-divider" />
                    ) : block.kind === 'table' ? (
                      <TableBlock text={block.text} readOnly={readOnly} onChange={(text) => patchBlock(block.id, (b) => ({ ...b, text }))} />
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
                        {block.kind === 'bullet' && <span className="bullet-dot" aria-hidden="true">{['•', '◦', '▪'][depth(block) % 3]}</span>}
                        {block.kind === 'toggle' && (
                          <button
                            type="button"
                            className={`toggle-caret${folded ? '' : ' open'}${hasKids ? '' : ' empty'}`}
                            aria-label={folded ? 'Expand' : 'Collapse'}
                            aria-expanded={!folded}
                            onClick={() => toggleFold(block.id)}
                          >
                            <ChevronRight size={16} aria-hidden="true" />
                          </button>
                        )}
                        {block.kind === 'numbered' && <span className="list-num" aria-hidden="true">{num}.</span>}
                        {block.kind === 'callout' && <Lightbulb size={18} className="callout-icon" aria-hidden="true" />}
                        <div className={`block-body${block.kind === 'todo' && block.checked ? ' done' : ''}`}>{editable(block, i)}</div>
                      </div>
                    )}
                    {block.kind === 'toggle' && !folded && !hasKids && !readOnly && (
                      <button
                        type="button"
                        className="toggle-empty"
                        onClick={() => {
                          const child = newBlock('paragraph', '', { indent: depth(block) + 1 });
                          insertAfter(block.id, child);
                          focusBlock(child.id, 0);
                        }}
                      >
                        Empty toggle. Click to add a block inside.
                      </button>
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
