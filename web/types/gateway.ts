/**
 * Gateway contract v0 passthrough payload. Contract v1 replaces this with
 * domain events; keep the mapping isolated in useChat until ticket 05 lands.
 */
export interface GatewayEvent {
  type: string;
  assistantMessageEvent?: {
    type: string;
    delta?: string;
  };
}

export interface GatewayPayload extends GatewayEvent {
  message?: string;
}
