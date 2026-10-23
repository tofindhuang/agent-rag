"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useState } from "react";
import {
  login as loginRequest,
  logout as logoutRequest,
  me,
  register as registerRequest,
  type AuthUser,
} from "@/lib/api/auth";
import { ApiError, userMessage } from "@/lib/api/errors";
import { AUTH_EXPIRED_EVENT } from "@/lib/api/session-events";
import { clearAccessToken } from "@/lib/api/token";

type AuthStatus = "loading" | "authenticated" | "anonymous" | "error";

interface AuthContextValue {
  status: AuthStatus;
  user: AuthUser | null;
  error: string | null;
  login: (email: string, password: string) => Promise<void>;
  register: (email: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
  /** Drops local auth state after a server-side token revocation. */
  endSession: () => void;
}

const AuthContext = createContext<AuthContextValue | null>(null);

/**
 * Owns authentication state. The access token lives in `lib/api/token`; this
 * provider only mirrors "who is signed in" for rendering.
 */
export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [status, setStatus] = useState<AuthStatus>("loading");
  const [user, setUser] = useState<AuthUser | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    // /me returns 401 without a token; http.ts then refreshes via the cookie
    // and retries once, which is how a page reload restores the session.
    me()
      .then((current) => {
        if (!active) return;
        setUser(current);
        setStatus("authenticated");
      })
      .catch((cause: unknown) => {
        if (!active) return;
        setUser(null);
        // Only an authentication failure means "anonymous"; a backend outage
        // must surface an error instead of redirecting to /login.
        if (cause instanceof ApiError && cause.status === 401) {
          setStatus("anonymous");
          return;
        }
        setError(userMessage(cause));
        setStatus("error");
      });
    return () => {
      active = false;
    };
  }, []);

  useEffect(() => {
    const expire = () => {
      setUser(null);
      setStatus("anonymous");
    };
    window.addEventListener(AUTH_EXPIRED_EVENT, expire);
    return () => window.removeEventListener(AUTH_EXPIRED_EVENT, expire);
  }, []);

  const login = useCallback(async (email: string, password: string) => {
    await loginRequest(email, password);
    const current = await me();
    setUser(current);
    setStatus("authenticated");
  }, []);

  const register = useCallback(
    async (email: string, password: string) => {
      await registerRequest(email, password);
      await login(email, password);
    },
    [login],
  );

  const logout = useCallback(async () => {
    try {
      await logoutRequest();
    } finally {
      clearAccessToken();
      setUser(null);
      setStatus("anonymous");
    }
  }, []);

  const endSession = useCallback(() => {
    clearAccessToken();
    setUser(null);
    setStatus("anonymous");
  }, []);

  const value = useMemo(
    () => ({ status, user, error, login, register, logout, endSession }),
    [status, user, error, login, register, logout, endSession],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);
  if (!context) throw new Error("useAuth must be used within AuthProvider");
  return context;
}
