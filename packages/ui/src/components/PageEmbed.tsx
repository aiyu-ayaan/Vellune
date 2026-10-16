import React from 'react';
import { ExternalLink, Pencil } from 'lucide-react';
import { Page } from '../model';
import { store } from '../store';
import { ReadOnlyBlocks } from './PublicPage';
import { PageIcon } from './PageIcon';

const SHOWN = 12;

/** `![[Page]]` on its own line: a live, read-only card of the page (Obsidian embed, AFFiNE linked doc). */
export const PageEmbed: React.FC<{ target?: Page; name: string; onEdit?: () => void }> = ({ target, name, onEdit }) => (
  <div className={`page-embed${target ? '' : ' missing'}`}>
    <div className="page-embed-head">
      {target && <PageIcon page={target} size={15} />}
      <span className="page-embed-title">{target ? target.title || 'Untitled' : name}</span>
      {target && (
        <button type="button" className="btn-icon" title="Open page" aria-label={`Open ${target.title || 'page'}`} onClick={() => store.open(target.id, 'page')}>
          <ExternalLink size={14} aria-hidden="true" />
        </button>
      )}
      {onEdit && (
        <button type="button" className="btn-icon" title="Edit embed" aria-label="Edit embed" onClick={onEdit}>
          <Pencil size={14} aria-hidden="true" />
        </button>
      )}
    </div>
    {target ? (
      <div className="page-embed-body">
        <ReadOnlyBlocks page={{ ...target, blocks: target.blocks.slice(0, SHOWN) }} />
        {target.blocks.length > SHOWN && <div className="page-embed-more">{target.blocks.length - SHOWN} more blocks…</div>}
      </div>
    ) : (
      <div className="page-embed-more">No page named “{name}” yet.</div>
    )}
  </div>
);

/** Target name when the whole block is a single `![[embed]]`. */
export const embedTarget = (text: string): string | null => /^!\[\[([^\]|#]+)(?:[#|][^\]]*)?\]\]$/.exec(text.trim())?.[1].trim() ?? null;
