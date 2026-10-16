// Workspace URLs: every view has a shareable, reloadable address.
//   /doc/{uuid}            page view
//   /doc/{uuid}/edgeless   whiteboard view
//   /graph  /databases[?tag=x]  /trash

import type { View } from './store';

export interface Route {
  view: View;
  pageId?: string;
  tag?: string | null;
}

const UUID = '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}';
const DOC = new RegExp(`^/doc/(${UUID})(/edgeless)?/?$`, 'i');

export function parseRoute(pathname: string, search = ''): Route | null {
  const doc = DOC.exec(pathname);
  if (doc) return { view: doc[2] ? 'edgeless' : 'page', pageId: doc[1].toLowerCase() };
  if (pathname === '/graph') return { view: 'graph' };
  if (pathname === '/trash') return { view: 'trash' };
  if (pathname === '/databases') return { view: 'database', tag: new URLSearchParams(search).get('tag') };
  return null;
}

export function routePath(r: Route): string {
  switch (r.view) {
    case 'graph':
      return '/graph';
    case 'trash':
      return '/trash';
    case 'database':
      return r.tag ? `/databases?tag=${encodeURIComponent(r.tag)}` : '/databases';
    default:
      return r.pageId ? `/doc/${r.pageId}${r.view === 'edgeless' ? '/edgeless' : ''}` : '/';
  }
}
