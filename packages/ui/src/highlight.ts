// Syntax highlighting for code blocks. Core build plus a fixed language set keeps the bundle small.

import hljs from 'highlight.js/lib/core';
import bash from 'highlight.js/lib/languages/bash';
import c from 'highlight.js/lib/languages/c';
import cpp from 'highlight.js/lib/languages/cpp';
import csharp from 'highlight.js/lib/languages/csharp';
import css from 'highlight.js/lib/languages/css';
import diff from 'highlight.js/lib/languages/diff';
import dockerfile from 'highlight.js/lib/languages/dockerfile';
import go from 'highlight.js/lib/languages/go';
import ini from 'highlight.js/lib/languages/ini';
import java from 'highlight.js/lib/languages/java';
import javascript from 'highlight.js/lib/languages/javascript';
import json from 'highlight.js/lib/languages/json';
import kotlin from 'highlight.js/lib/languages/kotlin';
import markdown from 'highlight.js/lib/languages/markdown';
import php from 'highlight.js/lib/languages/php';
import python from 'highlight.js/lib/languages/python';
import ruby from 'highlight.js/lib/languages/ruby';
import rust from 'highlight.js/lib/languages/rust';
import scss from 'highlight.js/lib/languages/scss';
import sql from 'highlight.js/lib/languages/sql';
import swift from 'highlight.js/lib/languages/swift';
import typescript from 'highlight.js/lib/languages/typescript';
import xml from 'highlight.js/lib/languages/xml';
import yaml from 'highlight.js/lib/languages/yaml';

/** [id stored in block.language, label]. Ids match Markdown fence info strings. */
export const CODE_LANGUAGES: [string, string][] = [
  ['', 'Plain text'],
  ['bash', 'Bash / Shell'],
  ['c', 'C'],
  ['cpp', 'C++'],
  ['csharp', 'C#'],
  ['css', 'CSS'],
  ['diff', 'Diff'],
  ['dockerfile', 'Dockerfile'],
  ['go', 'Go'],
  ['html', 'HTML / XML'],
  ['ini', 'INI / TOML'],
  ['java', 'Java'],
  ['javascript', 'JavaScript'],
  ['json', 'JSON'],
  ['kotlin', 'Kotlin'],
  ['markdown', 'Markdown'],
  ['php', 'PHP'],
  ['python', 'Python'],
  ['ruby', 'Ruby'],
  ['rust', 'Rust'],
  ['scss', 'SCSS'],
  ['sql', 'SQL'],
  ['swift', 'Swift'],
  ['typescript', 'TypeScript'],
  ['yaml', 'YAML'],
];

const defs = { bash, c, cpp, csharp, css, diff, dockerfile, go, ini, java, javascript, json, kotlin, markdown, php, python, ruby, rust, scss, sql, swift, typescript, xml, yaml };
for (const [name, def] of Object.entries(defs)) hljs.registerLanguage(name, def);
hljs.registerAliases(['sh', 'shell', 'zsh'], { languageName: 'bash' });
hljs.registerAliases(['js', 'jsx', 'mjs'], { languageName: 'javascript' });
hljs.registerAliases(['ts', 'tsx'], { languageName: 'typescript' });
hljs.registerAliases(['py'], { languageName: 'python' });
hljs.registerAliases(['rs'], { languageName: 'rust' });
hljs.registerAliases(['kt', 'kts'], { languageName: 'kotlin' });
hljs.registerAliases(['html', 'svg', 'vue'], { languageName: 'xml' });
hljs.registerAliases(['toml'], { languageName: 'ini' });
hljs.registerAliases(['yml'], { languageName: 'yaml' });
hljs.registerAliases(['cs'], { languageName: 'csharp' });
hljs.registerAliases(['md'], { languageName: 'markdown' });

/** Picker value for a stored fence language (aliases like `ts` map to `typescript`). */
export function canonicalLanguage(lang?: string): string {
  if (!lang) return '';
  const l = lang.toLowerCase();
  if (CODE_LANGUAGES.some(([id]) => id === l)) return l;
  const name = hljs.getLanguage(l)?.name?.toLowerCase();
  return CODE_LANGUAGES.find(([id, label]) => label.toLowerCase() === name || id === name)?.[0] ?? l;
}

const escape = (s: string) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');

/** HTML-escaped, highlighted markup whose text content equals `code` exactly. */
export function highlightCode(code: string, lang?: string): string {
  if (lang && hljs.getLanguage(lang)) {
    try {
      return hljs.highlight(code, { language: lang, ignoreIllegals: true }).value;
    } catch {
      /* fall through to plain */
    }
  }
  return escape(code);
}
