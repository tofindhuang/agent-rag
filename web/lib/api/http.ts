import { API_BASE } from "@/lib/env";
import { ApiError, toApiError } from "./errors";
import { notifyAuthExpired } from "./session-events";
import { clearAccessToken, extractAccessToken, getAccessToken, setAccessToken } from "./token";

interface RefreshResponse {
  accessToken?: unknown;
}

let refreshInFlight: Promise<string> | null = null;

/**
 * Uses the httpOnly refresh cookie to obtain a new access token. Single-flight:
 * concurrent 401s share one refresh request.
 */
async function refreshAccessToken(): Promise<string> {
  if (!refreshInFlight) {
    refreshInFlight = (async () => {
      const response = await fetch(`${API_BASE}/api/auth/refresh`, {
        method: "POST",
        credentials: "include",
      });
      if (!response.ok) {
        clearAccessToken();
        throw await toApiError(response);
      }
      const token = extractAccessToken((await response.json()) as RefreshResponse);
      if (!token) {
        clearAccessToken();
        throw new ApiError("服务响应缺少 access token", response.status);
      }
      setAccessToken(token);
      return token;
    })().finally(() => {
      refreshInFlight = null;
    });
  }
  return refreshInFlight;
}

export interface AuthFetchOptions {
  /** Default true: one silent refresh + retry on 401. */
  retryOn401?: boolean;
  /**
   * Default true: a 401 that survives the refresh means the session is gone
   * (clears the token and notifies AuthProvider). Business endpoints that use
   * 401 for a bad credential (change-password's wrong current password) pass
   * false so the error reaches the form instead of logging the user out.
   */
  expireOn401?: boolean;
}

/**
 * Authenticated fetch: attaches the in-memory Bearer token, sends cookies, and
 * retries once after a silent refresh on 401.
 */
export async function authFetch(
  path: string,
  init: RequestInit = {},
  options: AuthFetchOptions = {},
): Promise<Response> {
  const retryOn401 = options.retryOn401 ?? true;
  const expireOn401 = options.expireOn401 ?? true;
  const headers = new Headers(init.headers);
  const token = getAccessToken();
  if (token) headers.set("authorization", `Bearer ${token}`);

  const response = await fetch(`${API_BASE}${path}`, {
    ...init,
    headers,
    credentials: "include",
  });

  if (response.status === 401 && retryOn401) {
    try {
      await refreshAccessToken();
    } catch (cause) {
      clearAccessToken();
      // Let AuthProvider drop its authenticated state; AuthGate then redirects.
      notifyAuthExpired();
      throw cause;
    }
    const retried = await authFetch(path, init, { retryOn401: false, expireOn401 });
    if (retried.status === 401 && expireOn401) {
      clearAccessToken();
      notifyAuthExpired();
    }
    return retried;
  }
  return response;
}

/**
 * Unauthenticated JSON POST: no Bearer header and no 401 refresh. Public auth
 * endpoints must treat 401 as a credential error, not an expired session.
 */
export function publicPost(path: string, body: unknown): Promise<Response> {
  return fetch(`${API_BASE}${path}`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    credentials: "include",
    body: JSON.stringify(body),
  });
}

/** Authenticated JSON call; non-2xx responses become {@link ApiError}. */
export async function apiJson<T>(path: string, init: RequestInit = {}): Promise<T> {
  const response = await authFetch(path, init);
  if (!response.ok) throw await toApiError(response);
  return (await response.json()) as T;
}
