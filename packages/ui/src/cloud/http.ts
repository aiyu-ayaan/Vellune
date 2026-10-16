// Same-origin JSON client for /api/v1 (cookie session, CSRF header on writes).

export class ApiError extends Error {
  status: number;
  code: string;
  constructor(status: number, code: string, message: string) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
  }
}

const BASE = '/api/v1';

export async function req<T>(method: string, path: string, body?: unknown): Promise<T> {
  const init: RequestInit = { method, credentials: 'include' };
  if (method !== 'GET') {
    init.headers = { 'Content-Type': 'application/json', 'X-Vellune': '1' };
    if (body !== undefined) init.body = JSON.stringify(body);
  }
  let res: Response;
  try {
    res = await fetch(BASE + path, init);
  } catch {
    throw new ApiError(0, 'network', 'Network error. Check your connection.');
  }
  if (!res.ok) {
    let code = 'error';
    let message = res.statusText || 'Request failed';
    try {
      const j = await res.json();
      if (j?.error) {
        code = j.error.code ?? code;
        message = j.error.message ?? message;
      }
    } catch {
      /* non-JSON error body */
    }
    throw new ApiError(res.status, code, message);
  }
  if (res.status === 204) return undefined as T;
  return (await res.json()) as T;
}
