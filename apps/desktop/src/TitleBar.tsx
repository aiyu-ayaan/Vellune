import React, { useEffect, useState } from 'react';
import { getCurrentWindow } from '@tauri-apps/api/window';
import { Copy, Minus, Square, X } from 'lucide-react';

/** Height the title bar takes from the app; the shell reads it through --app-height. */
export const TITLE_BAR_HEIGHT = 36;

/** True inside the Tauri window; false when the same bundle is opened in a browser. */
const inTauri = typeof window !== 'undefined' && '__TAURI_INTERNALS__' in window;

/**
 * The window is undecorated (no native title bar or menu); this bar replaces it: the brand on the
 * left, a drag area (double-click maximizes) and minimize / maximize / close on the right.
 */
export const TitleBar: React.FC = () => {
  const [maximized, setMaximized] = useState(false);

  useEffect(() => {
    if (!inTauri) return;
    const win = getCurrentWindow();
    const sync = () => win.isMaximized().then(setMaximized).catch(() => {});
    sync();
    const off = win.onResized(sync);
    return () => {
      off.then((f) => f()).catch(() => {});
    };
  }, []);

  if (!inTauri) return null;
  const win = getCurrentWindow();
  return (
    <header className="titlebar" data-tauri-drag-region>
      <div className="titlebar-brand" data-tauri-drag-region>
        <svg width="14" height="14" viewBox="0 0 24 24" aria-hidden="true">
          <path d="M12 2a10 10 0 1 0 9.9 11.4A7.6 7.6 0 0 1 10.6 2.1 10 10 0 0 0 12 2z" fill="currentColor" />
        </svg>
        <span data-tauri-drag-region>Vellune</span>
      </div>
      <div className="titlebar-controls">
        <button type="button" aria-label="Minimize" title="Minimize" onClick={() => win.minimize()}>
          <Minus size={14} />
        </button>
        <button type="button" aria-label={maximized ? 'Restore' : 'Maximize'} title={maximized ? 'Restore' : 'Maximize'} onClick={() => win.toggleMaximize()}>
          {maximized ? <Copy size={12} /> : <Square size={12} />}
        </button>
        <button type="button" className="close" aria-label="Close" title="Close" onClick={() => win.close()}>
          <X size={15} />
        </button>
      </div>
    </header>
  );
};

export const desktopFrame = inTauri;
