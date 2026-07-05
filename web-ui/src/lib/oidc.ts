export interface LoginOptions {
  redirectUri?: string;
  scope?: string;
  silent?: boolean;
}

export interface InitOptions {
  loginRequired?: boolean;
}

export function isAuthEnabled(): boolean {
  return false;
}

export async function initAuth(_opts: InitOptions = {}): Promise<boolean> {
  return false;
}

export function getToken(): string | undefined {
  return undefined;
}

export async function refreshToken(_minValiditySeconds: number): Promise<void> {
  // no-op: no session to refresh
}

export function getClaims(): { username?: string; roles: string[] } | undefined {
  return undefined;
}

export async function login(_opts: LoginOptions = {}): Promise<void> {
  // no-op: no identity provider to redirect to
}

export async function logout(): Promise<void> {
  // no-op: no session to end
}

export function isAuthenticated(): boolean {
  return false;
}
