// Pasted/dropped/picked images become `image` blocks holding a data URL, so they sync and export
// with the page. Large rasters are downscaled first; until attachments land (object storage),
// MAX_BYTES keeps one page well under the sync update limit.

export const MAX_BYTES = 1_500_000;
const MAX_SIDE = 1600;

export const isImage = (f: File) => f.type.startsWith('image/');

const readDataUrl = (f: Blob) =>
  new Promise<string>((res, rej) => {
    const r = new FileReader();
    r.onload = () => res(r.result as string);
    r.onerror = () => rej(r.error);
    r.readAsDataURL(f);
  });

/** Data URL for an image file, or null when it is still over MAX_BYTES after downscaling. */
export async function imageToDataUrl(file: File): Promise<string | null> {
  // Animated/vector formats cannot be re-encoded through a canvas without losing what they are.
  if (file.type === 'image/gif' || file.type === 'image/svg+xml') return file.size <= MAX_BYTES ? readDataUrl(file) : null;
  try {
    const bmp = await createImageBitmap(file);
    const k = Math.min(1, MAX_SIDE / Math.max(bmp.width, bmp.height));
    const canvas = document.createElement('canvas');
    canvas.width = Math.round(bmp.width * k);
    canvas.height = Math.round(bmp.height * k);
    canvas.getContext('2d')!.drawImage(bmp, 0, 0, canvas.width, canvas.height);
    bmp.close();
    const keepPng = file.type === 'image/png' && file.size <= MAX_BYTES && k === 1;
    if (keepPng) return readDataUrl(file);
    const blob = await new Promise<Blob | null>((r) => canvas.toBlob(r, 'image/webp', 0.85));
    return blob && blob.size <= MAX_BYTES ? readDataUrl(blob) : null;
  } catch {
    return file.size <= MAX_BYTES ? readDataUrl(file) : null;
  }
}
