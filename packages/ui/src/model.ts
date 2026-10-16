// Client-side workspace model. Mirrors crates/document + crates/canvas until the Rust core
// is exposed to the UI (wasm for web, Tauri IPC for desktop).

export type BlockKind = 'paragraph' | 'heading' | 'todo' | 'bullet' | 'numbered' | 'quote' | 'callout' | 'code' | 'divider' | 'image';

export type NotionTextColor = 'gray' | 'brown' | 'orange' | 'yellow' | 'green' | 'blue' | 'purple' | 'pink' | 'red';
export type NotionColor = 'default' | NotionTextColor | `${NotionTextColor}_background`;

export interface Block {
  id: string;
  kind: BlockKind;
  text: string;
  level?: number; // heading 1-6
  checked?: boolean; // todo
  language?: string; // code
  src?: string; // image: data URL; text is the alt text
  color?: NotionColor; // UI-only; not exported to markdown
}

export type CanvasItemType = 'note' | 'sticky' | 'embed';

/** Positioned item on a page's edgeless canvas. `note` is the page's own document. */
export interface CanvasItem {
  id: string;
  type: CanvasItemType;
  x: number;
  y: number;
  w: number;
  h: number;
  text?: string;
  color?: string;
  pageId?: string; // embed target
}

/** Pen stroke; `points` is flat [x, y, pressure, ...] in world coordinates. */
export interface Stroke {
  id: string;
  points: number[];
  color: string;
  width: number;
  /** 'pen' | 'pencil' | 'highlighter', set by native clients; missing draws as a pen. */
  tool?: string;
}

export interface Page {
  id: string;
  title: string;
  blocks: Block[];
  tags: string[];
  aliases: string[];
  favorite: boolean;
  journal?: string; // YYYY-MM-DD for daily notes
  canvas: { items: CanvasItem[]; strokes: Stroke[] };
  /** Imported frontmatter minus title/tags/aliases, re-emitted on export. */
  extraFrontmatter?: string;
  createdAt: number;
  updatedAt: number;
  deletedAt?: number;
  /** Containing folder or parent doc; missing/unknown parent means the page sits at the root. */
  parentId?: string;
  /** A folder in the sidebar tree: holds docs and folders, has no content of its own. */
  folder?: boolean;
  /** Manual sort position among siblings (ascending); unset sorts after ordered siblings. */
  order?: number;
  /** Starter page nobody has edited yet; device-local, never synced. */
  seeded?: boolean;
}

export const newId = (): string => crypto.randomUUID();

export const newBlock = (kind: BlockKind = 'paragraph', text = '', extra: Partial<Block> = {}): Block => ({
  id: newId(),
  kind,
  text,
  ...extra,
});

export function newPage(title: string, init: Partial<Page> = {}): Page {
  const now = Date.now();
  return {
    id: newId(),
    title,
    blocks: [newBlock()],
    tags: [],
    aliases: [],
    favorite: false,
    canvas: { items: [], strokes: [] },
    createdAt: now,
    updatedAt: now,
    ...init,
  };
}

export const todayKey = (d = new Date()): string =>
  `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
