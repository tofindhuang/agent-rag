export type AuditOutcome = "success" | "denied" | "failed";

export interface AuditEvent {
  id: number;
  actorUserId: number | null;
  actorType: string;
  action: string;
  targetType: string | null;
  targetId: string | null;
  outcome: AuditOutcome;
  ip: string | null;
  createdAt: string;
  detail: string | null;
}

export interface AuditPage {
  items: AuditEvent[];
  nextBeforeId: number | null;
}

export type AdminTab = "overview" | "users" | "trace" | "audit";
