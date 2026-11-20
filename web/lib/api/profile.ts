import { ApiError, toApiError } from "./errors";
import { authFetch } from "./http";
import type {
  ProfileCollection,
  ProfileFact,
  ProfileFactStatus,
  ProfileSettings,
} from "@/types/profile";

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
    factTitle: String(raw.factTitle ?? raw.factText ?? ""),
    factText: String(raw.factText ?? ""),
    collectionId: String(raw.collectionId ?? ""),
    status: parseStatus(raw.status),
    supersedesId: typeof raw.supersedesId === "string" ? raw.supersedesId : null,
    sourceSessionId:
      typeof raw.sourceSessionId === "string" ? raw.sourceSessionId : null,
    createdAt: typeof raw.createdAt === "string" ? raw.createdAt : null,
    updatedAt: typeof raw.updatedAt === "string" ? raw.updatedAt : null,
  };
}

function parseCollection(value: unknown): ProfileCollection {
  const raw = asRecord(value);
  const systemKey =
    raw.systemKey === "general" || raw.systemKey === "inbox" ? raw.systemKey : null;
  return {
    id: String(raw.id ?? ""),
    name: String(raw.name ?? ""),
    systemKey,
    factCount: typeof raw.factCount === "number" ? raw.factCount : 0,
  };
}

export async function fetchProfileFacts(
  status: "active" | "all" = "active",
  collectionId?: string,
): Promise<ProfileFact[]> {
  const query = new URLSearchParams({ status });
  if (collectionId) query.set("collectionId", collectionId);
  const response = await authFetch(`/api/profile/facts?${query.toString()}`);
  const body = await readJson(response);
  if (!Array.isArray(body)) throw new ApiError("服务响应格式不正确");
  return body.map(parseFact);
}

export async function updateProfileFact(
  id: string,
  input: {
    factTitle: string;
    factText: string;
    category: string;
    collectionId?: string;
  },
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

export async function moveProfileFacts(
  factIds: string[],
  collectionId: string,
): Promise<void> {
  const response = await authFetch("/api/profile/facts/move", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ factIds, collectionId }),
  });
  if (!response.ok) throw await toApiError(response);
}

export async function fetchProfileCollections(): Promise<ProfileCollection[]> {
  const body = await readJson(await authFetch("/api/profile/collections"));
  if (!Array.isArray(body)) throw new ApiError("服务响应格式不正确");
  return body.map(parseCollection);
}

export async function createProfileCollection(name: string): Promise<ProfileCollection> {
  const response = await authFetch("/api/profile/collections", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ name }),
  });
  return parseCollection(await readJson(response));
}

export async function renameProfileCollection(
  id: string,
  name: string,
): Promise<ProfileCollection> {
  const response = await authFetch(`/api/profile/collections/${id}`, {
    method: "PATCH",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ name }),
  });
  return parseCollection(await readJson(response));
}

export async function deleteProfileCollection(id: string): Promise<void> {
  const response = await authFetch(`/api/profile/collections/${id}`, { method: "DELETE" });
  if (!response.ok) throw await toApiError(response);
}

export async function fetchSessionCollections(sessionId: string): Promise<string[]> {
  const body = asRecord(
    await readJson(await authFetch(`/api/chat/sessions/${sessionId}/profile-collections`)),
  );
  const ids = body.collectionIds;
  return Array.isArray(ids) ? ids.map(String) : [];
}

export async function setSessionCollections(
  sessionId: string,
  collectionIds: string[],
): Promise<void> {
  const response = await authFetch(`/api/chat/sessions/${sessionId}/profile-collections`, {
    method: "PUT",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ collectionIds }),
  });
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
