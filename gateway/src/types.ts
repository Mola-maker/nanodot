import type { FreeGate } from './quota';

export interface GatewayEnv {
  AI: Ai;
  ASSETS: Fetcher;
  FREE_GATE: DurableObjectNamespace<FreeGate>;
  GATEWAY_KEY_HASHES: string;
}
