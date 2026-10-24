import { ApiError, toApiError } from "./errors";
import { authFetch } from "./http";
import type { ProfileFact, ProfileFactStatus, ProfileSettings } from "@/types/profile";

async function readJson(response: Response): Promise<unknown> {
  if (!response.ok) throw await toApiError(response);
  return response.json();
}

function asRecord(value: unknown): Record<string, unknown> {
  return typeof value === "object" && value !== null ? (value as Record<string, unknown>) : {};
}

function parseStatus(value: unknown): ProfileFactStatus {
  return value === "superseded" || value === "deleted" ? value : "active";
}

function parseFact(value: unknown): ProfileFact {
  const raw = asRecord(value);
  return {
    id: String(raw.id ?? ""),
    category: String(raw.category ?? ""),
    factKey: String(raw.factKey ?? ""),
    factValue: String(raw.factValue ?? ""),
    factText: String(raw.factText ?? ""),
    status: parseStatus(raw.status),
    supersedesId: typeof raw.supersedesId === "string" ? raw.supersedesId : null,
    sourceSessionId:
      typeof raw.sourceSessionId === "string" ? raw.sourceSessionId : null,
    createdAt: typeof raw.createdAt === "string" ? raw.createdAt : null,
    updatedAt: typeof raw.updatedAt === "string" ? raw.updatedAt : null,
  };
}

export async function fetchProfileFacts(
  status: "active" | "all" = "active",
): Promise<ProfileFact[]> {
  const response = await authFetch(`/api/profile/facts?status=${status}`);
  const body = await readJson(response);
  if (!Array.isArray(body)) throw new ApiError("服务响应格式不正确");
  return body.map(parseFact);
}

export async function updateProfileFact(
  id: string,
  input: { factText: string; category: string },
): Promise<ProfileFact> {
  const response = await authFetch(`/api/profile/facts/${id}`, {
    method: "PATCH",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(input),
  });
  return parseFact(await readJson(response));
}

export async function deleteProfileFact(id: string): Promise<void> {
  const response = await authFetch(`/api/profile/facts/${id}`, { method: "DELETE" });
  if (!response.ok) throw await toApiError(response);
}

export async function fetchProfileSettings(): Promise<ProfileSettings> {
  const body = asRecord(await readJson(await authFetch("/api/profile/settings")));
  return { enabled: body.enabled !== false };
}

export async function updateProfileSettings(enabled: boolean): Promise<ProfileSettings> {
  const response = await authFetch("/api/profile/settings", {
    method: "PUT",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ enabled }),
  });
  const body = asRecord(await readJson(response));
  return { enabled: body.enabled !== false };
}
