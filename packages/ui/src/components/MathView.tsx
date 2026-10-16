/// <reference path="../katex.d.ts" />
import React, { useEffect, useState } from 'react';

type Katex = typeof import('katex').default;
let loading: Promise<Katex> | null = null;
// KaTeX (and its fonts) load on the first math block, not with the app.
const loadKatex = () =>
  (loading ??= Promise.all([import('katex'), import('katex/dist/katex.min.css')]).then(([m]) => m.default));

/** Rendered display math; `trust: false` keeps \href, \url and friends from producing links. */
export const MathView: React.FC<{ tex: string; onClick?: (e: React.MouseEvent) => void }> = ({ tex, onClick }) => {
  const [html, setHtml] = useState<string | null>(null);
  useEffect(() => {
    let live = true;
    void loadKatex().then((k) => live && setHtml(k.renderToString(tex || '\\square', { displayMode: true, throwOnError: false, trust: false, maxSize: 50, maxExpand: 1000 })));
    return () => {
      live = false;
    };
  }, [tex]);
  return (
    <div
      className={`math-view${tex ? '' : ' empty'}`}
      role={onClick ? 'button' : 'math'}
      tabIndex={onClick ? 0 : undefined}
      aria-label={onClick ? `Edit math: ${tex}` : tex}
      onClick={onClick}
      onKeyDown={(e) => onClick && (e.key === 'Enter' || e.key === ' ') && (e.preventDefault(), onClick(e as unknown as React.MouseEvent))}
      // KaTeX output is generated markup from escaped TeX, not user HTML.
      dangerouslySetInnerHTML={html === null ? undefined : { __html: html }}
    >
      {html === null ? tex : undefined}
    </div>
  );
};
