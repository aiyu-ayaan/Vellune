// Minimal typings for the part of KaTeX Vellune uses (the package ships none).
declare module 'katex' {
  interface Options {
    displayMode?: boolean;
    throwOnError?: boolean;
    trust?: boolean;
    maxSize?: number;
    maxExpand?: number;
  }
  const katex: { renderToString(tex: string, options?: Options): string };
  export default katex;
}
declare module 'katex/dist/katex.min.css';
