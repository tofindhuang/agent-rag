import type { LoadTestClient, MessageSummary } from "./api.js";

export interface VerifyRecord {
  email: string;
  sessionId: string | null;
  turnId: string;
  status: string;
  errorClass: string;
  assistantTextChars: number;
}

export interface VerifyResult {
  sessionsChecked: number;
  turnsChecked: number;
  missing: string[];
  duplicates: string[];
  roleMismatches: string[];
  textMismatches: string[];
  errors: string[];
}

type VerifyClient = Pick<LoadTestClient, "login" | "listMessages">;

async function fetchAllMessages(
  client: VerifyClient,
  token: string,
  sessionId: string,
): Promise<MessageSummary[]> {
  const all: MessageSummary[] = [];
  let beforeId: number | undefined;
  for (let page = 0; page < 50; page += 1) {
    const response = await client.listMessages(token, sessionId, {
      limit: 100,
      ...(beforeId === undefined ? {} : { beforeId }),
    });
    all.push(...response.items);
    if (!response.hasMore || response.nextBeforeId === null) break;
    beforeId = response.nextBeforeId;
  }
  return all;
}

/**
 * Restart durability check: every completed turn must appear exactly once as a
 * user message with at least one assistant reply in the MySQL projection.
 * Repeated turnIds would indicate a retry that created a new turn instead of
 * resuming the existing one.
 */
export async function verifySettledMessages(
  client: VerifyClient,
  records: VerifyRecord[],
  password: string,
): Promise<VerifyResult> {
  const result: VerifyResult = {
    sessionsChecked: 0,
    turnsChecked: 0,
    missing: [],
    duplicates: [],
    roleMismatches: [],
    textMismatches: [],
    errors: [],
  };

  const byEmail = new Map<string, VerifyRecord[]>();
  for (const record of records) {
    if (!record.sessionId) continue;
    const list = byEmail.get(record.email) ?? [];
    list.push(record);
    byEmail.set(record.email, list);
  }

  for (const [email, userRecords] of byEmail) {
    let token: string;
    try {
      token = (await client.login(email, password)).accessToken;
    } catch (error) {
      result.errors.push(`${email}: login failed (${messageOf(error)})`);
      continue;
    }
    const bySession = new Map<string, VerifyRecord[]>();
    for (const record of userRecords) {
      const sessionId = record.sessionId as string;
      const list = bySession.get(sessionId) ?? [];
      list.push(record);
      bySession.set(sessionId, list);
    }
    for (const [sessionId, sessionRecords] of bySession) {
      let messages: MessageSummary[];
      try {
        messages = await fetchAllMessages(client, token, sessionId);
      } catch (error) {
        result.errors.push(`${sessionId}: history failed (${messageOf(error)})`);
        continue;
      }
      result.sessionsChecked += 1;
      const byTurn = new Map<string, MessageSummary[]>();
      for (const message of messages) {
        if (!message.turnId) continue;
        const list = byTurn.get(message.turnId) ?? [];
        list.push(message);
        byTurn.set(message.turnId, list);
      }
      for (const record of sessionRecords) {
        const turnMessages = byTurn.get(record.turnId) ?? [];
        const userMessages = turnMessages.filter((message) => message.role === "user");
        const assistantMessages = turnMessages.filter((message) => message.role === "assistant");
        if (record.status !== "completed") {
          if (userMessages.length > 1) result.duplicates.push(record.turnId);
          continue;
        }
        result.turnsChecked += 1;
        if (userMessages.length === 0) result.missing.push(record.turnId);
        if (userMessages.length > 1) result.duplicates.push(record.turnId);
        if (assistantMessages.length === 0) result.roleMismatches.push(record.turnId);
        if (record.assistantTextChars > 0) {
          const projectedChars = assistantMessages.reduce(
            (sum, message) => sum + message.content.length,
            0,
          );
          if (projectedChars !== record.assistantTextChars) {
            result.textMismatches.push(record.turnId);
          }
        }
      }
    }
  }

  return result;
}

function messageOf(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
