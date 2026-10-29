import { API_BASE } from "@/lib/env";
import { ApiError, toApiError } from "./errors";
import { apiJson, authFetch, publicPost } from "./http";
import { clearAccessToken, extractAccessToken, setAccessToken } from "./token";

export type UserRole = "user" | "admin";

export interface AuthUser {
  userId: number;
  email: string;
  role: UserRole;
}

interface RegisterResponse {
  userId?: unknown;
  email?: unknown;
}

/** Signs in and keeps the returned access token in memory. */
export async function login(email: string, password: string): Promise<void> {
  const response = await publicPost("/api/auth/login", { email, password });
  if (!response.ok) throw await toApiError(response);
  const token = extractAccessToken(await response.json());
  if (!token) throw new ApiError("服务响应缺少 access token", response.status);
  setAccessToken(token);
}

export async function register(email: string, password: string): Promise<void> {
  const response = await publicPost("/api/auth/register", { email, password });
  if (!response.ok) throw await toApiError(response);
  const data = (await response.json()) as RegisterResponse;
  if (typeof data.userId !== "number") {
    throw new ApiError("服务响应缺少 userId", response.status);
  }
}

/** Revokes the refresh token server-side and clears the in-memory access token. */
export async function logout(): Promise<void> {
  try {
    const response = await publicPost("/api/auth/logout", {});
    if (!response.ok && response.status !== 401) throw await toApiError(response);
  } finally {
    clearAccessToken();
  }
}

/** Current user; the first call after a page reload refreshes via cookie. */
export function me(): Promise<AuthUser> {
  return apiJson<AuthUser>("/api/auth/me");
}

/**
 * Replaces the password after verifying the current one. The server revokes
 * every refresh token, so the caller must end the local session afterwards.
 */
export async function changePassword(
  currentPassword: string,
  newPassword: string,
): Promise<void> {
  const response = await authFetch(
    "/api/auth/change-password",
    {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ currentPassword, newPassword }),
    },
    { expireOn401: false },
  );
  if (!response.ok) throw await toApiError(response);
}

/** Revokes every refresh token of the account; the current access token dies at expiry. */
export async function logoutAll(): Promise<void> {
  const response = await authFetch("/api/auth/logout-all", { method: "POST" });
  if (!response.ok) throw await toApiError(response);
}

export interface OAuthConfig {
  enabled: boolean;
  providerName: string;
}

/** Public capability check for the login/register pages. */
export async function oauthConfig(): Promise<OAuthConfig> {
  const response = await fetch(`${API_BASE}/api/auth/oauth/config`, {
    credentials: "include",
  });
  if (!response.ok) throw await toApiError(response);
  const data = (await response.json()) as {
    enabled?: unknown;
    providerName?: unknown;
  };
  return {
    enabled: data.enabled === true,
    providerName:
      typeof data.providerName === "string" && data.providerName.length > 0
        ? data.providerName
        : "OIDC",
  };
}

/** Top-level navigation; the callback establishes the refresh session. */
export function oauthStartUrl(): string {
  return `${API_BASE}/api/auth/oauth/start`;
}
