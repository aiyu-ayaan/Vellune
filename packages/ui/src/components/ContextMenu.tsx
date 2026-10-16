// App-wide custom right-click menu. One host renders whatever menu `openContextMenu` was last given.

import React, { useEffect, useLayoutEffect, useRef, useState, useSyncExternalStore } from 'react';
import type { LucideIcon } from 'lucide-react';

export type MenuEntry =
  | { label: string; icon?: LucideIcon; onSelect: () => void; danger?: boolean; disabled?: boolean; hint?: string }
  | 'separator';

interface MenuState {
  x: number;
  y: number;
  items: MenuEntry[];
  /** Element focused before the menu opened; focus returns there on close. */
  ret: HTMLElement | null;
}

let state: MenuState | null = null;
const listeners = new Set<() => void>();
const emit = () => listeners.forEach((l) => l());

export function openContextMenu(e: { clientX: number; clientY: number; preventDefault(): void; stopPropagation(): void }, items: MenuEntry[]) {
  e.preventDefault();
  e.stopPropagation();
  state = { x: e.clientX, y: e.clientY, items, ret: document.activeElement as HTMLElement | null };
  emit();
}

/** Open from a keyboard-focused element (Shift+F10 / ContextMenu key), anchored to its corner. */
export function openContextMenuAt(el: HTMLElement, items: MenuEntry[]) {
  const r = el.getBoundingClientRect();
  state = { x: r.left + 8, y: r.bottom, items, ret: el };
  emit();
}

export function closeContextMenu() {
  const ret = state?.ret;
  state = null;
  emit();
  ret?.focus?.();
}

const subscribe = (l: () => void) => {
  listeners.add(l);
  return () => listeners.delete(l);
};

export const ContextMenuHost: React.FC = () => {
  const menu = useSyncExternalStore(subscribe, () => state);
  const ref = useRef<HTMLDivElement>(null);
  const [pos, setPos] = useState<{ left: number; top: number } | null>(null);

  // Keep the menu inside the viewport: flip left/up when it would overflow.
  useLayoutEffect(() => {
    if (!menu || !ref.current) return setPos(null);
    const { width, height } = ref.current.getBoundingClientRect();
    const left = menu.x + width > innerWidth - 8 ? Math.max(8, menu.x - width) : menu.x;
    const top = menu.y + height > innerHeight - 8 ? Math.max(8, menu.y - height) : menu.y;
    setPos({ left, top });
    ref.current.querySelector<HTMLElement>('button:not(:disabled)')?.focus();
  }, [menu]);

  useEffect(() => {
    if (!menu) return;
    const close = (e: Event) => {
      if (e.type === 'mousedown' && ref.current?.contains(e.target as Node)) return;
      closeContextMenu();
    };
    addEventListener('mousedown', close, true);
    addEventListener('resize', close);
    addEventListener('blur', close);
    addEventListener('scroll', close, true);
    return () => {
      removeEventListener('mousedown', close, true);
      removeEventListener('resize', close);
      removeEventListener('blur', close);
      removeEventListener('scroll', close, true);
    };
  }, [menu]);

  if (!menu) return null;
  const onKeyDown = (e: React.KeyboardEvent) => {
    const items = Array.from(ref.current?.querySelectorAll<HTMLElement>('button:not(:disabled)') ?? []);
    const i = items.indexOf(document.activeElement as HTMLElement);
    if (e.key === 'Escape' || e.key === 'Tab') {
      e.preventDefault();
      closeContextMenu();
    } else if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault();
      items[(i + (e.key === 'ArrowDown' ? 1 : items.length - 1)) % items.length]?.focus();
    } else if (e.key === 'Home' || e.key === 'End') {
      e.preventDefault();
      items[e.key === 'Home' ? 0 : items.length - 1]?.focus();
    }
  };

  return (
    <div
      ref={ref}
      className="popover context-menu"
      role="menu"
      style={{ position: 'fixed', left: pos?.left ?? menu.x, top: pos?.top ?? menu.y, visibility: pos ? 'visible' : 'hidden' }}
      onKeyDown={onKeyDown}
      onContextMenu={(e) => e.preventDefault()}
    >
      {menu.items.map((it, i) =>
        it === 'separator' ? (
          <div key={`s${i}`} className="popover-sep" role="separator" />
        ) : (
          <button
            key={it.label}
            type="button"
            role="menuitem"
            className={`menu-item${it.danger ? ' danger' : ''}`}
            disabled={it.disabled}
            onClick={() => {
              closeContextMenu();
              it.onSelect();
            }}
          >
            {it.icon ? <it.icon size={14} aria-hidden="true" /> : <span className="menu-icon-pad" aria-hidden="true" />}
            <span>{it.label}</span>
            {it.hint && <span className="menu-end menu-hint">{it.hint}</span>}
          </button>
        ),
      )}
    </div>
  );
};

/**
 * The browser's own menu is replaced everywhere except where it is genuinely useful: text fields
 * (spellcheck, paste) and native links in published pages. Areas with their own menu call
 * `openContextMenu`, which stops propagation before this runs.
 */
export function suppressNativeContextMenu(e: MouseEvent) {
  const t = e.target as HTMLElement | null;
  if (t?.closest('input, textarea, [contenteditable="true"], .allow-native-menu')) return;
  e.preventDefault();
}
