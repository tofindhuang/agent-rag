export type ProfileFactStatus = "active" | "superseded" | "deleted";

export interface ProfileFact {
  id: string;
  category: string;
  factKey: string;
  factValue: string;
  factText: string;
  status: ProfileFactStatus;
  supersedesId: string | null;
  sourceSessionId: string | null;
  createdAt: string | null;
  updatedAt: string | null;
}

export interface ProfileSettings {
  enabled: boolean;
}
