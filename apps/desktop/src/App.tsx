import React from 'react';
import { VelluneApp } from '@vellune/ui';
import { TITLE_BAR_HEIGHT, TitleBar, desktopFrame } from './TitleBar';
import './desktop.css';

export const App: React.FC = () => {
  return (
    <div
      className="desktop-root"
      // The shell sizes itself from --app-height; leave room for our title bar in the Tauri window.
      style={desktopFrame ? ({ '--app-height': `calc(100vh - ${TITLE_BAR_HEIGHT}px)` } as React.CSSProperties) : undefined}
    >
      <TitleBar />
      <VelluneApp />
    </div>
  );
};
