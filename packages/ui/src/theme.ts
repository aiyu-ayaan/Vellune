export type Theme = 'light' | 'dark' | 'system';

const KEY = 'vellune.theme';
const mq = typeof window !== 'undefined' && window.matchMedia ? window.matchMedia('(prefers-color-scheme: dark)') : null;
let current: Theme = 'system';

function apply(): void {
  if (typeof document === 'undefined') return;
  const resolved = current === 'system' ? (mq?.matches ? 'dark' : 'light') : current;
  document.documentElement.setAttribute('data-theme', resolved);
}

export function getTheme(): Theme {
  return current;
}

export function setTheme(t: Theme): void {
  current = t;
  try {
    localStorage.setItem(KEY, t);
  } catch {
    /* storage unavailable */
  }
  apply();
}

// Init once at module load.
try {
  const saved = localStorage.getItem(KEY);
  if (saved === 'light' || saved === 'dark' || saved === 'system') current = saved;
} catch {
  /* storage unavailable */
}
mq?.addEventListener?.('change', () => {
  if (current === 'system') apply();
});
apply();
