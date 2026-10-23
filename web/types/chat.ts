export type ChatRole = "user" | "assistant";
export type ChatStatus = "streaming" | "complete" | "aborted" | "error";
export type ToolStatus = "calling" | "running" | "done" | "error";

export interface ToolActivity {
  toolCallId: string;
  name: string;
  argsText: string;
  phase?: string;
  output: string;
  status: ToolStatus;
}

export interface ChatMessage {
  role: ChatRole;
  content: string;
  thinking: string;
  tools: ToolActivity[];
  status?: ChatStatus;
}
