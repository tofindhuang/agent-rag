import type { RunIdentity } from "./identity.js";

/** Header set for gateway -> Java/sandbox tool callbacks (contract v1 §8). */
export function buildToolHeaders(
  identity: RunIdentity,
  toolCallId: string,
  apiToken?: string,
): Record<string, string> {
  const headers: Record<string, string> = {
    "X-User-Id": identity.userId,
    "X-Session-Id": identity.sessionId,
    "X-Turn-Id": identity.turnId,
    "X-Run-Id": identity.runId,
    "X-Tool-Call-Id": toolCallId,
    "X-Request-Id": identity.requestId,
  };
  if (apiToken) headers.Authorization = `Bearer ${apiToken}`;
  return headers;
}
