import React, { useEffect, useState } from 'react';
import { blobObjectUrl, isBlobRef } from '../cloud/blobs';
import { sync } from '../cloud/sync';

/** An image block's picture: inline data URLs as-is, `vblob:` refs fetched and decrypted. */
export const BlobImage: React.FC<{
  pageId: string;
  src: string;
  alt: string;
  className: string;
  pageKey?: CryptoKey;
  draggable?: boolean;
  onDragStart?: React.DragEventHandler<HTMLImageElement>;
}> = ({ pageId, src, alt, className, pageKey, ...drag }) => {
  const [resolved, setResolved] = useState<string | null>(isBlobRef(src) ? null : src);
  const [failed, setFailed] = useState(false);
  useEffect(() => {
    if (!isBlobRef(src)) return setResolved(src);
    let live = true;
    setResolved(null);
    setFailed(false);
    blobObjectUrl(pageId, src, pageKey ?? sync.pageKey(pageId)).then(
      (u) => live && setResolved(u),
      () => live && setFailed(true),
    );
    return () => { live = false; };
  }, [pageId, src, pageKey]);
  if (resolved) return <img className={className} src={resolved} alt={alt} {...drag} />;
  return <div className={`${className} image-placeholder`} role="img" aria-label={alt}>{failed ? 'Image unavailable' : 'Loading image…'}</div>;
};
