/** Browser event used when the session can no longer be refreshed. */
export const AUTH_EXPIRED_EVENT = "auth:expired";

export function notifyAuthExpired(): void {
  if (typeof window !== "undefined") window.dispatchEvent(new Event(AUTH_EXPIRED_EVENT));
}
