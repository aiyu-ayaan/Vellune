import { describe, expect, it } from 'vitest';
import { canonicalLanguage, highlightCode } from './highlight';

const text = (html: string) => html.replace(/<[^>]+>/g, '').replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&#x27;/g, "'").replace(/&amp;/g, '&');

describe('highlightCode', () => {
  it('colors known languages and keeps the exact source text', () => {
    const src = 'const x: number = 1; // <b> & "q"\nfn';
    const html = highlightCode(src, 'typescript');
    expect(html).toContain('hljs-keyword');
    expect(text(html)).toBe(src);
  });
  it('escapes plain text and unknown languages', () => {
    expect(highlightCode('<script>', '')).toBe('&lt;script&gt;');
    expect(highlightCode('a<b', 'nope')).toBe('a&lt;b');
  });
  it('maps fence aliases to picker ids', () => {
    expect(canonicalLanguage('ts')).toBe('typescript');
    expect(canonicalLanguage('sh')).toBe('bash');
    expect(canonicalLanguage('Rust')).toBe('rust');
    expect(canonicalLanguage(undefined)).toBe('');
  });
});
