// Pasted/dropped/picked images. Large rasters are downscaled and re-encoded before they are
// sealed and uploaded to the encrypted blob store (cloud/blobs.ts). Pages not in the cloud yet
// (local mode, offline, unsynced) keep the image inline as a data URL, capped at INLINE_MAX.

export const UPLOAD_MAX = 6_000_000; // under the server's 8 MiB sealed-blob limit
export const INLINE_MAX = 1_500_000; // keeps inline images well under the sync update limit
const MAX_SIDE = 2048;

export const isImage = (f: File) => f.type.startsWith('image/');

export const readDataUrl = (f: Blob) =>
  new Promise<string>((res, rej) => {
    const r = new FileReader();
    r.onload = () => res(r.result as string);
    r.onerror = () => rej(r.error);
    r.readAsDataURL(f);
  });

/** The image as it will be stored, or null when still over UPLOAD_MAX after downscaling. */
export async function prepareImage(file: File): Promise<Blob | null> {
  const fits = (b: Blob | null) => (b && b.size <= UPLOAD_MAX ? b : null);
  // Animated/vector formats cannot be re-encoded through a canvas without losing what they are.
  if (file.type === 'image/gif' || file.type === 'image/svg+xml') return fits(file);
  try {
    const bmp = await createImageBitmap(file);
    const k = Math.min(1, MAX_SIDE / Math.max(bmp.width, bmp.height));
    if (k === 1 && file.size <= INLINE_MAX) {
      bmp.close();
      return file;
    }
    const canvas = document.createElement('canvas');
    canvas.width = Math.round(bmp.width * k);
    canvas.height = Math.round(bmp.height * k);
    canvas.getContext('2d')!.drawImage(bmp, 0, 0, canvas.width, canvas.height);
    bmp.close();
    return fits(await new Promise<Blob | null>((r) => canvas.toBlob(r, 'image/webp', 0.85)));
  } catch {
    return fits(file);
  }
}
