// Templates: any page tagged #template. Using one copies its blocks with fresh ids and fills
// {{date}}, {{time}} and {{title}}, like Obsidian's core Templates plugin.

import { Block, Page, newId, todayKey } from './model';

export const isTemplate = (p: Page) => !p.deletedAt && !p.folder && p.tags.some((t) => t.toLowerCase() === 'template');

export function fill(text: string, title: string, now = new Date()): string {
  return text
    .replace(/\{\{date\}\}/g, todayKey(now))
    .replace(/\{\{time\}\}/g, `${String(now.getHours()).padStart(2, '0')}:${String(now.getMinutes()).padStart(2, '0')}`)
    .replace(/\{\{title\}\}/g, title);
}

/** The template's blocks as new blocks for a page titled `title`. */
export function instantiate(tpl: Page, title: string, now = new Date()): Block[] {
  return tpl.blocks.map((b) => ({ ...b, id: newId(), text: fill(b.text, title, now) }));
}

/** Fields for a new page made from `tpl` (its tags minus #template, its icon). */
export function pageFromTemplate(tpl: Page, title: string, now = new Date()): Partial<Page> {
  return {
    blocks: instantiate(tpl, title, now),
    tags: tpl.tags.filter((t) => t.toLowerCase() !== 'template'),
    icon: tpl.icon,
  };
}
