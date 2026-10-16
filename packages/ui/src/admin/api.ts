import { ApiError, req } from '../cloud/http';

export { ApiError };

export type User = { id: string; email: string; name: string; is_admin: boolean; created_at: string };
export type AdminUser = User & { disabled: boolean; providers: string[]; pages: number; has_keys: boolean };
export type OAuthProviderId = 'github' | 'google';
export type OAuthProviderConfig = {
  provider: OAuthProviderId;
  enabled: boolean;
  client_id: string;
  has_secret: boolean;
  redirect_uri: string;
};
export type ServerSettings = {
  allow_signups: boolean;
  allowed_email_domains: string[];
  app_url: string;
  max_update_bytes: number;
};
export type AuditEvent = { id: number; actor_email: string | null; action: string; target: string | null; created_at: string };
export type AdminStats = {
  users: number;
  admins: number;
  disabled_users: number;
  pages: number;
  updates: number;
  update_bytes: number;
  active_sessions: number;
  online_connections: number;
};

export const adminApi = {
  me: () => req<{ user: User }>('GET', '/auth/me'),
  stats: () => req<AdminStats>('GET', '/admin/stats'),
  users: (q: string, offset: number, limit: number) =>
    req<{ users: AdminUser[]; total: number }>(
      'GET',
      `/admin/users?q=${encodeURIComponent(q)}&offset=${offset}&limit=${limit}`,
    ),
  updateUser: (id: string, patch: { is_admin?: boolean; disabled?: boolean; name?: string }) =>
    req<AdminUser>('PATCH', `/admin/users/${encodeURIComponent(id)}`, patch),
  deleteUser: (id: string) => req<void>('DELETE', `/admin/users/${encodeURIComponent(id)}`),
  revokeSessions: (id: string) => req<void>('DELETE', `/admin/users/${encodeURIComponent(id)}/sessions`),
  oauth: () => req<OAuthProviderConfig[]>('GET', '/admin/oauth'),
  updateOAuth: (provider: OAuthProviderId, body: { enabled: boolean; client_id: string; client_secret?: string }) =>
    req<OAuthProviderConfig>('PUT', `/admin/oauth/${provider}`, body),
  settings: () => req<ServerSettings>('GET', '/admin/settings'),
  updateSettings: (s: ServerSettings) => req<ServerSettings>('PUT', '/admin/settings', s),
  audit: (limit: number) => req<AuditEvent[]>('GET', `/admin/audit?limit=${limit}`),
};
