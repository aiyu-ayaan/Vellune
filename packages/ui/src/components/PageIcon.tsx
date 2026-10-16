import React, { useEffect, useRef, useState } from 'react';
import { Calendar, FileText, LucideIcon } from 'lucide-react';
import { Page } from '../model';

/** The page's emoji, or `fallback` (a document glyph by default) when it has none. */
export const PageIcon: React.FC<{ page: Pick<Page, 'icon' | 'journal'>; size?: number; className?: string; fallback?: LucideIcon }> = ({
  page,
  size = 16,
  className,
  fallback,
}) => {
  if (page.icon)
    return (
      <span className={`page-emoji${className ? ` ${className}` : ''}`} style={{ fontSize: size * 0.95, width: size, height: size }} aria-hidden="true">
        {page.icon}
      </span>
    );
  const Icon = fallback ?? (page.journal ? Calendar : FileText);
  return <Icon size={size} aria-hidden="true" className={className} />;
};

// A curated set keeps the picker small and dependency-free; any emoji can still be typed or pasted.
const GROUPS: { label: string; emojis: string }[] = [
  { label: 'Common', emojis: '📄 📝 📌 📎 📚 📖 🗂️ 📁 🗒️ 📋 ✅ ☑️ 💡 🧠 🎯 🚀 ⭐ 🔥 ⚡ 🌙' },
  { label: 'Work', emojis: '💼 📊 📈 📉 🗓️ ⏰ 🧾 🧮 🔧 🛠️ ⚙️ 🧪 🔬 💻 🖥️ 📱 🔒 🔑 🧩 🏗️' },
  { label: 'Life', emojis: '🏠 🍳 ☕ 🍎 🏃 🧘 🎵 🎨 🎬 📷 ✈️ 🌍 🌱 🌸 🐱 🐶 ❤️ 🎁 🎉 😊' },
  { label: 'Symbols', emojis: '❓ ❗ ⚠️ ✨ 🔴 🟠 🟡 🟢 🔵 🟣 ⚫ ⚪ 🔺 🔷 ➕ ♻️ 🏷️ 🔖 📍 🧭' },
];

/** First emoji (with modifiers and ZWJ joins) or first character, so a pasted sentence is not the icon. */
export const firstGlyph = (s: string): string | undefined =>
  /^(?:\p{Regional_Indicator}{2}|\p{Extended_Pictographic}(?:\p{Emoji_Modifier}|\uFE0F|\u200D\p{Extended_Pictographic})*)/u.exec(s)?.[0] ?? [...s][0];

/** Grid of emojis plus a free-text field; `onPick(undefined)` removes the icon. */
export const IconPicker: React.FC<{ current?: string; onPick: (icon: string | undefined) => void; onClose: () => void }> = ({ current, onPick, onClose }) => {
  const ref = useRef<HTMLDivElement>(null);
  const [custom, setCustom] = useState('');

  useEffect(() => {
    ref.current?.querySelector<HTMLElement>('button')?.focus();
    const onDown = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) onClose();
    };
    document.addEventListener('mousedown', onDown);
    return () => document.removeEventListener('mousedown', onDown);
  }, [onClose]);

  const pick = (icon: string | undefined) => {
    onPick(icon);
    onClose();
  };

  return (
    <div
      ref={ref}
      className="popover icon-picker"
      role="dialog"
      aria-label="Choose page icon"
      onKeyDown={(e) => {
        if (e.key === 'Escape') {
          e.stopPropagation();
          onClose();
        }
      }}
    >
      {GROUPS.map((g) => (
        <div key={g.label}>
          <div className="popover-label">{g.label}</div>
          <div className="icon-grid">
            {g.emojis.split(' ').map((em) => (
              <button
                key={em}
                type="button"
                className={`icon-cell${em === current ? ' active' : ''}`}
                aria-label={`Use ${em} as icon`}
                aria-pressed={em === current}
                onClick={() => pick(em)}
              >
                {em}
              </button>
            ))}
          </div>
        </div>
      ))}
      <div className="popover-sep" />
      <form
        className="icon-custom"
        onSubmit={(e) => {
          e.preventDefault();
          const first = firstGlyph(custom.trim());
          if (first) pick(first);
        }}
      >
        <input value={custom} onChange={(e) => setCustom(e.target.value)} placeholder="Type or paste any emoji" aria-label="Custom icon" />
        <button type="submit" className="menu-item" disabled={!custom.trim()}>
          Use
        </button>
      </form>
      {current && (
        <button type="button" className="menu-item" onClick={() => pick(undefined)}>
          Remove icon
        </button>
      )}
    </div>
  );
};
