export type ProfileFactStatus = "active" | "superseded" | "deleted";

export type ProfileCollectionSystemKey = "general" | "inbox" | null;

export interface ProfileCollection {
  id: string;
  name: string;
  systemKey: ProfileCollectionSystemKey;
  factCount: number;
}

export interface ProfileFact {
  id: string;
  category: string;
  factKey: string;
  factValue: string;
  factTitle: string;
  factText: string;
  collectionId: string;
  status: ProfileFactStatus;
  supersedesId: string | null;
  sourceSessionId: string | null;
  createdAt: string | null;
  updatedAt: string | null;
}

export interface ProfileSettings {
  enabled: boolean;
}
