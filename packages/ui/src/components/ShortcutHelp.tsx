import React, { useEffect, useRef } from 'react';
import { X } from 'lucide-react';

const mac = typeof navigator !== 'undefined' && /Mac|iPhone|iPad/.test(navigator.platform);
const MOD = mac ? '⌘' : 'Ctrl';

const GROUPS: { title: string; keys: [string, string][] }[] = [
  {
    title: 'Workspace',
    keys: [
      [`${MOD} K`, 'Search pages and commands'],
      [`${MOD} \\`, 'Show or hide the sidebar'],
      [`${MOD} /`, 'This list'],
      [`${MOD} Alt Shift H`, 'Version history'],
    ],
  },
  {
    title: 'Writing',
    keys: [
      ['/', 'Insert a block or template'],
      ['[[', 'Link a page'],
      ['@', 'Mention a date or page'],
      ['#', 'Tag the page'],
      ['Tab / Shift Tab', 'Nest or un-nest a block'],
      ['Shift Enter', 'Line break inside a block'],
      [`${MOD} Enter`, 'Leave a code block'],
    ],
  },
  {
    title: 'Formatting',
    keys: [
      [`${MOD} B`, 'Bold'],
      [`${MOD} I`, 'Italic'],
      [`${MOD} E`, 'Inline code'],
      [`${MOD} Shift S`, 'Strikethrough'],
      ['# , ## , ###', 'Heading (at line start)'],
      ['- , 1. , [] , + , >', 'Bullet, number, to-do, toggle, quote'],
    ],
  },
  {
    title: 'Canvas',
    keys: [
      ['V P H R E S', 'Select, pen, highlighter, shapes, eraser, sticky'],
      [`${MOD} Z / ${MOD} Shift Z`, 'Undo / redo'],
      [`${MOD} wheel`, 'Zoom at the pointer'],
      ['Delete', 'Remove the selected item'],
    ],
  },
];

export const ShortcutHelp: React.FC<{ onClose: () => void }> = ({ onClose }) => {
  const ref = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    ref.current?.focus();
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose();
    addEventListener('keydown', onKey);
    return () => removeEventListener('keydown', onKey);
  }, [onClose]);
  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-dialog shortcut-help" role="dialog" aria-modal="true" aria-label="Keyboard shortcuts" onClick={(e) => e.stopPropagation()}>
        <div className="shortcut-head">
          <h2>Keyboard shortcuts</h2>
          <button ref={ref} type="button" className="btn-icon" aria-label="Close" onClick={onClose}>
            <X size={16} aria-hidden="true" />
          </button>
        </div>
        <div className="shortcut-grid">
          {GROUPS.map((g) => (
            <section key={g.title}>
              <h3>{g.title}</h3>
              <dl>
                {g.keys.map(([k, v]) => (
                  <div key={k} className="shortcut-row">
                    <dt>{v}</dt>
                    <dd>
                      {k.split(' / ').map((alt, i) => (
                        <React.Fragment key={alt}>
                          {i > 0 && ' / '}
                          <kbd>{alt}</kbd>
                        </React.Fragment>
                      ))}
                    </dd>
                  </div>
                ))}
              </dl>
            </section>
          ))}
        </div>
      </div>
    </div>
  );
};
