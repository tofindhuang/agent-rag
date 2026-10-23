/** Identity of the currently active Run, injected into tool callbacks. */
export interface RunIdentity {
  userId: string;
  sessionId: string;
  turnId: string;
  runId: string;
  requestId: string;
}

/** Mutable holder handed to tool extensions; one per AgentSession. */
export interface RunRef {
  current: RunIdentity | null;
}
