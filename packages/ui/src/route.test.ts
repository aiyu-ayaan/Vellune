import { describe, expect, it } from 'vitest';
import { parseRoute, routePath } from './route';

describe('routes', () => {
  const id = '3f2b8c1e-9a4d-4e2f-8b7a-1c2d3e4f5a6b';
  it('round-trips every view', () => {
    for (const r of [{ view: 'page', pageId: id }, { view: 'edgeless', pageId: id }, { view: 'graph' }, { view: 'trash' }, { view: 'database', tag: 'a b' }] as const) {
      const path = routePath(r);
      const [p, q] = path.split('?');
      expect(parseRoute(p, q ? `?${q}` : '')).toMatchObject(r);
    }
  });
  it('rejects unknown paths and malformed ids', () => {
    expect(parseRoute('/doc/not-a-uuid')).toBeNull();
    expect(parseRoute('/admin')).toBeNull();
    expect(parseRoute('/')).toBeNull();
  });
});
