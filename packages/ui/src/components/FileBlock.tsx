import React, { useState } from 'react';
import { Download, ExternalLink, FileText, Paperclip } from 'lucide-react';
import { blobObjectUrl, isBlobRef } from '../cloud/blobs';
import { sync } from '../cloud/sync';

const SAFE_TO_OPEN = new Set(['application/pdf', 'image/png', 'image/jpeg', 'image/gif', 'image/webp', 'text/plain']);

const mimeOf = (src: string) => (isBlobRef(src) ? src.split(':').slice(2).join(':') : /^data:([^;,]+)/.exec(src)?.[1] ?? '');

/** An attached file: decrypted only when opened or downloaded. PDFs and images open in a new tab. */
export const FileBlock: React.FC<{ pageId: string; name: string; src?: string; pageKey?: CryptoKey }> = ({ pageId, name, src, pageKey }) => {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(false);
  if (!src) return null;
  const mime = mimeOf(src);
  // Only types that cannot run script may open in a tab: a blob: URL shares the app's origin, so an
  // attached HTML or SVG file would otherwise execute with access to this workspace.
  const viewable = SAFE_TO_OPEN.has(mime);
  const ext = /\.([a-z0-9]{1,6})$/i.exec(name)?.[1]?.toUpperCase() ?? (mime.split('/')[1] ?? 'FILE').toUpperCase();

  const resolve = async () => (isBlobRef(src) ? blobObjectUrl(pageId, src, pageKey ?? sync.pageKey(pageId)) : src);
  const act = async (open: boolean) => {
    setBusy(true);
    setError(false);
    try {
      const url = await resolve();
      const a = document.createElement('a');
      a.href = url;
      if (open) a.target = '_blank';
      else a.download = name || 'attachment';
      a.rel = 'noopener';
      a.click();
    } catch {
      setError(true);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="file-block">
      <span className="file-icon" aria-hidden="true">
        {mime === 'application/pdf' ? <FileText size={20} /> : <Paperclip size={20} />}
      </span>
      <span className="file-meta">
        <span className="file-name">{name || 'Attachment'}</span>
        <span className="file-sub">{error ? 'Unavailable — not synced to this device yet' : busy ? 'Decrypting…' : ext}</span>
      </span>
      {viewable && (
        <button type="button" className="btn-icon" title="Open" aria-label={`Open ${name}`} onClick={() => void act(true)} disabled={busy}>
          <ExternalLink size={16} aria-hidden="true" />
        </button>
      )}
      <button type="button" className="btn-icon" title="Download" aria-label={`Download ${name}`} onClick={() => void act(false)} disabled={busy}>
        <Download size={16} aria-hidden="true" />
      </button>
    </div>
  );
};
