/**
 * `/api/channels` — the chat apps (Feishu, DingTalk, WeCom, Telegram) your Nanodot lives in.
 * Kept apart from api.ts so the channels screen can be wired in with one import. Secrets are
 * written here and never read back: a secret field only says `has_value`.
 */
import { request } from "./api";

export interface ChannelField {
  key: string;
  label: string;
  kind: "string" | "secret" | "bool" | "choice";
  required: boolean;
  help: string;
  choices: string[];
  default: string;
  /** non-secret fields: what is set */
  value?: string | boolean;
  /** secret fields: whether something is set */
  has_value?: boolean;
}

export type ChannelState = "off" | "unconfigured" | "missing_sdk" | "connecting" | "connected" | "error";

export interface PairedChat {
  sender_id: string;
  sender_name: string;
  chat_id: string;
  deliver: boolean;
  approved_at: number | null;
  thread: string;
}

export interface ChannelInfo {
  name: string;
  label: string;
  enabled: boolean;
  status: { state: ChannelState; detail: string };
  sdk_available: boolean;
  install: string;
  console_url: string;
  supports_edit: boolean;
  fields: ChannelField[];
  allow_from: string[];
  group_policy: "mention" | "open";
  paired: PairedChat[];
  /** a Feishu QR login is under way */
  login: boolean;
}

export interface PendingPairing {
  code: string;
  channel: string;
  sender_id: string;
  sender_name: string;
  chat_id: string;
  created_at: number;
  expires_at: number;
}

export interface ChannelsView {
  channels: ChannelInfo[];
  pending: PendingPairing[];
}

export interface ChannelUpdate {
  enabled?: boolean;
  settings?: Record<string, string | boolean>;
  allow_from?: string[];
  group_policy?: "mention" | "open";
}

export interface LoginSession {
  device_code: string;
  url: string;
  interval: number;
  expires_in: number;
  /** base64 PNG of the QR code, "" when the server could not draw one */
  qr_png: string;
}

export interface LoginPoll {
  status: "pending" | "succeeded" | "failed";
  app_id?: string;
  error?: string;
}

const json = (method: string, body: unknown): RequestInit => ({ method, body: JSON.stringify(body) });
const enc = encodeURIComponent;

export const channelsApi = {
  view: () => request<ChannelsView>("/api/channels"),
  update: (name: string, body: ChannelUpdate) => request<ChannelsView>(`/api/channels/${enc(name)}`, json("PUT", body)),
  reload: () => request<ChannelsView>("/api/channels/reload", { method: "POST" }),
  test: (name: string, chatId = "") =>
    request<{ ok: boolean; detail: string }>(`/api/channels/${enc(name)}/test`, json("POST", { chat_id: chatId })),
  approve: (code: string) =>
    request<ChannelsView & { paired: PairedChat & { channel: string } }>(`/api/channels/pairing/${enc(code)}/approve`, {
      method: "POST",
    }),
  deny: (code: string) => request<ChannelsView>(`/api/channels/pairing/${enc(code)}/deny`, { method: "POST" }),
  setDeliver: (name: string, senderId: string, deliver: boolean) =>
    request<ChannelsView>(`/api/channels/${enc(name)}/chats/${enc(senderId)}`, json("PUT", { deliver })),
  removeChat: (name: string, senderId: string) =>
    request<ChannelsView>(`/api/channels/${enc(name)}/chats/${enc(senderId)}`, { method: "DELETE" }),
  loginBegin: (name: string, domain: "feishu" | "lark") =>
    request<LoginSession>(`/api/channels/${enc(name)}/login`, json("POST", { domain })),
  loginPoll: (name: string, deviceCode: string) =>
    request<LoginPoll>(`/api/channels/${enc(name)}/login/${enc(deviceCode)}`),
  deliver: (text: string) => request<{ sent: number }>("/api/channels/deliver", json("POST", { text })),
};
