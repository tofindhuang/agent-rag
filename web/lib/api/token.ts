/**
 * Access token kept in module memory only. No localStorage/sessionStorage, so
 * an XSS cannot read the long-lived credential; the refresh token stays in an
 * httpOnly cookie.
 */
let accessToken: string | null = null;

export function getAccessToken(): string | null {
  return accessToken;
}

export function setAccessToken(token: string): void {
  accessToken = token;
}

export function clearAccessToken(): void {
  accessToken = null;
}

/** Validates the login/refresh payload and returns the token, if present. */
export function extractAccessToken(value: unknown): string | null {
  if (typeof value !== "object" || value === null) return null;
  const token = (value as { accessToken?: unknown }).accessToken;
  return typeof token === "string" && token.length > 0 ? token : null;
}
