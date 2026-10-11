import type { FreeGate } from './quota';

export interface GatewayEnv {
  AI: Ai;
  TTS_ZH_VERIFIED?: string;
  TTS_ZH_LANG?: string;
  ASSETS: Fetcher;
  FREE_GATE: DurableObjectNamespace<FreeGate>;
  GATEWAY_KEY_HASHES: string;
}
