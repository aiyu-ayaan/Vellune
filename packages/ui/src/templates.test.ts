import { describe, expect, it } from 'vitest';
import { instantiate, isTemplate, pageFromTemplate } from './templates';
import { newBlock, newPage } from './model';

describe('templates', () => {
  const tpl = newPage('Meeting', {
    tags: ['Template', 'work'],
    icon: '📋',
    blocks: [newBlock('heading', '{{title}} — {{date}}', { level: 1 }), newBlock('todo', 'Agenda at {{time}}', { checked: false, indent: 1 })],
  });
  const now = new Date(2026, 9, 17, 9, 5);

  it('recognises #template pages only', () => {
    expect(isTemplate(tpl)).toBe(true);
    expect(isTemplate(newPage('x', { tags: ['templates'] }))).toBe(false);
    expect(isTemplate({ ...tpl, deletedAt: 1 })).toBe(false);
  });

  it('copies blocks with fresh ids and fills placeholders', () => {
    const bs = instantiate(tpl, 'Standup', now);
    expect(bs.map((b) => b.text)).toEqual(['Standup — 2026-10-17', 'Agenda at 09:05']);
    expect(bs[0].id).not.toBe(tpl.blocks[0].id);
    expect(bs[1].indent).toBe(1);
    const init = pageFromTemplate(tpl, 'Standup', now);
    expect(init.tags).toEqual(['work']);
    expect(init.icon).toBe('📋');
  });
});
