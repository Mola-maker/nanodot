import { Check, ExternalLink, Loader2, MessageSquare, QrCode, RefreshCw, Send, Trash2, X } from "lucide-react";
import { useEffect, useRef, useState, type ReactNode } from "react";
import {
  channelsApi,
  type ChannelField,
  type ChannelInfo,
  type ChannelsView,
  type LoginSession,
  type PendingPairing,
} from "../channels-api";
import { PageBar } from "../components/BackBar";
import { inputCls, primaryBtn, secondaryBtn } from "../components/Form";
import { LoadError } from "../components/LoadError";
import { MuseCaption, MuseCard, MuseDivider, MuseRow, MuseSectionLabel, MuseSwitchRow } from "../components/MuseList";
import { useT } from "../i18n";
import { useStore } from "../store";
import { cx } from "../util";

/**
 * Chat apps (「聊天入口」) — your Nanodot inside Feishu, DingTalk, WeCom or Telegram
 * (docs/channels.md). One card per app: the switch, its credentials (secrets are written and
 * never shown again), who may talk to it, the chats that are paired and which of them get
 * what the Muse does on its own. Codes people were shown when they first wrote to the bot
 * wait at the top for a yes or a no. The Chinese lines are in i18n/zh-CN.ts with everyone
 * else's; the screen had its own dictionary until the second audit.
 */

const STEPS: Record<string, string[]> = {
  feishu: [
    "In the Feishu developer console create a custom app and add the Bot capability.",
    "Permissions: im:message, im:message.p2p_msg:readonly, im:message.group_at_msg:readonly, im:resource.",
    "Events & callbacks: subscription mode Long connection; add im.message.receive_v1; callback mode Long connection; add card.action.trigger.",
    "Copy App ID and App Secret from Credentials & Basic Info, then publish a version.",
  ],
  dingtalk: [
    "In the DingTalk developer console create an internal app and add the Robot capability.",
    "Set the message receive mode to Stream.",
    "Copy Client ID (AppKey) and Client Secret (AppSecret) from Credentials & Basic Info.",
    "Add the robot send-message permission and publish the version.",
  ],
  wecom: [
    "In the WeCom admin console open Intelligent robot and create a robot in API mode with a long connection.",
    "Copy the Bot ID and Secret.",
    "Add the robot to the chats where it should listen.",
  ],
  telegram: [
    "In Telegram talk to @BotFather, send /newbot and copy the token.",
    "Direct messages work at once. For a group, add the bot to it; with Group Privacy on it only hears @-mentions and replies.",
    "If api.telegram.org is out of reach from this computer, set a proxy below.",
  ],
};

const ICONS: Record<string, ReactNode> = {
  telegram: <Send size={20} />,
};

function stateLabel(t: (k: string) => string, c: ChannelInfo): string {
  const s = c.status.state;
  if (s === "off") return t("Off");
  if (s === "unconfigured") return t("Needs settings");
  if (s === "missing_sdk") return t("Not installed");
  if (s === "connecting") return t("Connecting…");
  if (s === "connected") return t("Connected");
  return t("Error");
}

function Dot({ state }: { state: ChannelInfo["status"]["state"] }) {
  const color =
    state === "connected" ? "bg-emerald-500" : state === "error" ? "bg-red-500" : state === "connecting" ? "bg-amber-400" : "bg-border";
  return <span aria-hidden className={cx("inline-block h-2 w-2 rounded-full", color)} />;
}

export function ChannelsScreen() {
  const { toast } = useStore();
  const t = useT();
  const [view, setView] = useState<ChannelsView | null>(null);
  // the first failure is shown with a retry; the poll every five seconds does not toast on each miss
  const [loadError, setLoadError] = useState<string | null>(null);

  const reload = async () => {
    try {
      setView(await channelsApi.view());
      setLoadError(null);
    } catch (e) {
      setLoadError((e as Error).message || "Could not load.");
    }
  };
  useEffect(() => {
    void reload();
    const timer = window.setInterval(() => void reload(), 5000);
    const onFocus = () => void reload();
    window.addEventListener("focus", onFocus);
    return () => {
      window.clearInterval(timer);
      window.removeEventListener("focus", onFocus);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const run = async (work: () => Promise<ChannelsView>, done?: string) => {
    try {
      setView(await work());
      if (done) toast(done);
    } catch (e) {
      toast((e as Error).message);
    }
  };

  return (
    <div className="flex h-full flex-col">
      <PageBar
        title={t("Chat apps")}
        description={t("Talk to your Muse from the chat apps you already use. Nothing needs a public address: each app keeps a long connection open.")}
      />
      <div className="flex-1 overflow-y-auto pb-8">
        {view === null && loadError && <LoadError message={loadError} onRetry={() => void reload()} />}
        {view === null && !loadError && (
          <div className="flex justify-center py-10 text-muted">
            <Loader2 className="animate-spin" size={20} />
          </div>
        )}
        {view && view.pending.length > 0 && (
          <>
            <MuseSectionLabel>{t("Waiting for a yes")}</MuseSectionLabel>
            <MuseCard>
              {view.pending.map((p, i) => (
                <div key={p.code}>
                  {i > 0 && <MuseDivider inset={16} />}
                  <PendingRow
                    p={p}
                    label={view.channels.find((c) => c.name === p.channel)?.label ?? p.channel}
                    onApprove={() => run(() => channelsApi.approve(p.code))}
                    onDeny={() => run(() => channelsApi.deny(p.code))}
                  />
                </div>
              ))}
            </MuseCard>
          </>
        )}
        {view?.channels.map((c) => <ChannelCard key={c.name} c={c} run={run} reload={reload} />)}
        {view && (
          <MuseCaption>{t("Each chat is one conversation of your Muse; its replies stream back as they are written.")}</MuseCaption>
        )}
      </div>
    </div>
  );
}

function PendingRow({ p, label, onApprove, onDeny }: { p: PendingPairing; label: string; onApprove: () => void; onDeny: () => void }) {
  const t = useT();
  const who = p.sender_name || p.sender_id;
  return (
    <div className="flex items-center gap-3 px-4 py-3">
      <div className="min-w-0 flex-1">
        <p className="truncate text-[15px]">{t("{name} wrote to the bot. Approve to let them talk to your Muse.", { name: `${who} · ${label}` })}</p>
        <p className="text-[13px] text-muted">{t("Code {code}", { code: p.code })}</p>
      </div>
      <button type="button" className={cx(primaryBtn, "px-3 py-2")} onClick={onApprove} aria-label={t("Approve")}>
        <Check size={16} /> {t("Approve")}
      </button>
      <button type="button" className={cx(secondaryBtn, "px-3 py-2")} onClick={onDeny} aria-label={t("Deny")}>
        <X size={16} />
      </button>
    </div>
  );
}

function ChannelCard({
  c,
  run,
  reload,
}: {
  c: ChannelInfo;
  run: (work: () => Promise<ChannelsView>, done?: string) => Promise<void>;
  reload: () => Promise<void>;
}) {
  const t = useT();
  const { toast } = useStore();
  const [open, setOpen] = useState(false);
  const [edits, setEdits] = useState<Record<string, string>>({});
  // secret fields the person asked to clear (an emptied input otherwise keeps the saved value)
  const [cleared, setCleared] = useState<string[]>([]);
  const [allow, setAllow] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [testing, setTesting] = useState(false);
  const [steps, setSteps] = useState(false);
  const [login, setLogin] = useState<LoginSession | null>(null);
  const [loginNote, setLoginNote] = useState("");
  const [lark, setLark] = useState(false);
  const pollRef = useRef<number | null>(null);

  const dirty = Object.keys(edits).length > 0 || allow !== null;
  const allowText = allow ?? c.allow_from.join("\n");

  const save = async () => {
    setSaving(true);
    try {
      const body: { settings?: Record<string, string>; allow_from?: string[] } = {};
      const settings: Record<string, string> = {};
      for (const [key, value] of Object.entries(edits)) {
        const field = c.fields.find((f) => f.key === key);
        if (field?.kind === "secret" && value === "" && !cleared.includes(key)) continue;
        settings[key] = value;
      }
      if (Object.keys(settings).length) body.settings = settings;
      if (allow !== null) body.allow_from = allow.split(/[\n,]/).map((s) => s.trim()).filter(Boolean);
      await run(() => channelsApi.update(c.name, body), t("Saved."));
      setEdits({});
      setCleared([]);
      setAllow(null);
    } finally {
      setSaving(false);
    }
  };

  const test = async () => {
    setTesting(true);
    try {
      const r = await channelsApi.test(c.name);
      toast(r.detail);
    } catch (e) {
      toast((e as Error).message);
    } finally {
      setTesting(false);
    }
  };

  const stopPolling = () => {
    if (pollRef.current !== null) window.clearTimeout(pollRef.current);
    pollRef.current = null;
  };
  useEffect(() => stopPolling, []);

  const beginLogin = async () => {
    setLoginNote("");
    try {
      const session = await channelsApi.loginBegin(c.name, lark ? "lark" : "feishu");
      setLogin(session);
      const started = Date.now();
      const poll = async () => {
        try {
          const r = await channelsApi.loginPoll(c.name, session.device_code);
          if (r.status === "succeeded") {
            setLogin(null);
            setLoginNote(t("The bot was created and is connecting."));
            await reload();
            return;
          }
          if (r.status === "failed" || Date.now() - started > session.expires_in * 1000) {
            setLogin(null);
            setLoginNote(t("The login did not finish ({error}).", { error: r.error ?? "expired" }));
            return;
          }
        } catch (e) {
          setLogin(null);
          setLoginNote((e as Error).message);
          return;
        }
        pollRef.current = window.setTimeout(() => void poll(), Math.max(2, session.interval) * 1000);
      };
      pollRef.current = window.setTimeout(() => void poll(), Math.max(2, session.interval) * 1000);
    } catch (e) {
      toast((e as Error).message);
    }
  };

  const cancelLogin = () => {
    stopPolling();
    setLogin(null);
  };

  const missing = c.fields.filter((f) => f.required && (f.kind === "secret" ? !f.has_value : !f.value)).map((f) => f.label);

  return (
    <>
      <MuseSectionLabel>{c.label}</MuseSectionLabel>
      <MuseCard>
        <MuseRow
          icon={ICONS[c.name] ?? <MessageSquare size={20} />}
          label={c.label}
          value={stateLabel(t, c)}
          trailing={<Dot state={c.status.state} />}
          onClick={() => setOpen((v) => !v)}
        />
        <MuseDivider />
        <MuseSwitchRow
          label={t("Switched on")}
          checked={c.enabled}
          disabled={!c.sdk_available && !c.enabled}
          onChange={(v) => void run(() => channelsApi.update(c.name, { enabled: v }))}
        />
        {c.status.state === "error" && c.status.detail && (
          <p className="px-4 pb-3 text-[13px] leading-[18px] text-red-600 dark:text-red-400">{c.status.detail}</p>
        )}
        {c.status.state === "unconfigured" && (
          <p className="px-4 pb-3 text-[13px] leading-[18px] text-muted">
            {t("Missing")}: {c.status.detail || missing.join(", ")}
          </p>
        )}
        {!c.sdk_available && (
          <div className="px-4 pb-3 text-[13px] leading-[18px] text-muted">
            {t("Install on the computer running Nanodot, then switch it on again:")}
            <code className="mt-1 block select-all rounded-lg bg-surface-2 px-2 py-1 text-[12.5px] text-fg">{c.install}</code>
          </div>
        )}
        {(open || c.enabled) && (
          <>
            <MuseDivider inset={16} />
            <div className="space-y-3 px-4 py-3">
              {c.fields.map((f) => (
                <FieldInput
                  key={f.key}
                  f={f}
                  value={edits[f.key]}
                  onChange={(v) => {
                    setEdits((e) => ({ ...e, [f.key]: v }));
                    if (v !== "") setCleared((list) => list.filter((k) => k !== f.key));
                  }}
                  onClear={() => {
                    setEdits((e) => ({ ...e, [f.key]: "" }));
                    setCleared((list) => (list.includes(f.key) ? list : [...list, f.key]));
                  }}
                />
              ))}
              <label className="block">
                <span className="mb-1 block text-[13px] text-muted">{t("In groups, reply")}</span>
                <select
                  className={inputCls}
                  value={c.group_policy}
                  onChange={(e) => void run(() => channelsApi.update(c.name, { group_policy: e.target.value as "mention" | "open" }))}
                >
                  <option value="mention">{t("only when @-mentioned")}</option>
                  <option value="open">{t("to every message")}</option>
                </select>
              </label>
              <label className="block">
                <span className="mb-1 block text-[13px] text-muted">{t("Always allowed (ids, one per line)")}</span>
                <textarea className={cx(inputCls, "min-h-[56px] font-mono text-[13px]")} value={allowText} onChange={(e) => setAllow(e.target.value)} rows={2} />
                <span className="mt-1 block text-[12.5px] leading-[17px] text-muted">
                  {t("People listed here never need a pairing code. A lone * lets anyone in; do that only on a bot nobody else can reach.")}
                </span>
              </label>
              <div className="flex flex-wrap gap-2">
                <button type="button" className={primaryBtn} disabled={!dirty || saving} onClick={() => void save()}>
                  {saving ? t("Saving") : t("Save")}
                </button>
                <button type="button" className={secondaryBtn} disabled={testing || c.status.state !== "connected"} onClick={() => void test()}>
                  <RefreshCw size={14} className={testing ? "animate-spin" : ""} /> {testing ? t("Sending") : t("Send a test message")}
                </button>
                {c.name === "feishu" && !login && (
                  <button type="button" className={secondaryBtn} onClick={() => void beginLogin()}>
                    <QrCode size={14} /> {t("Create the bot by QR code")}
                  </button>
                )}
                <button type="button" className={secondaryBtn} onClick={() => setSteps((v) => !v)} aria-expanded={steps}>
                  {t("Set up")}
                </button>
              </div>
              {c.name === "feishu" && !login && (
                <label className="flex items-center gap-2 text-[13px] text-muted">
                  <input type="checkbox" checked={lark} onChange={(e) => setLark(e.target.checked)} />
                  {t("Lark (international) instead of 飞书")}
                </label>
              )}
              {login && (
                <div className="rounded-2xl bg-surface-2 p-3 text-center">
                  {login.qr_png ? (
                    <img src={`data:image/png;base64,${login.qr_png}`} alt="QR" className="mx-auto h-44 w-44 rounded-lg bg-white p-1" />
                  ) : null}
                  <p className="mt-2 text-[13px] text-muted">{t("Scan with Feishu, confirm the new bot, and the App ID and Secret are saved for you.")}</p>
                  <p className="text-[13px] text-muted">{t("Waiting for the scan…")}</p>
                  <div className="mt-2 flex justify-center gap-2">
                    <a href={login.url} target="_blank" rel="noreferrer" className={cx(secondaryBtn, "px-3 py-1.5")}>
                      <ExternalLink size={14} /> {t("Open in Feishu")}
                    </a>
                    <button type="button" className={cx(secondaryBtn, "px-3 py-1.5")} onClick={cancelLogin}>
                      {t("Cancel")}
                    </button>
                  </div>
                </div>
              )}
              {loginNote && <p className="text-[13px] text-muted">{loginNote}</p>}
              {steps && (
                <div className="rounded-2xl bg-surface-2 p-3 text-[13.5px] leading-[19px]">
                  <ol className="list-decimal space-y-1 pl-5">
                    {(STEPS[c.name] ?? []).map((s) => (
                      <li key={s}>{t(s)}</li>
                    ))}
                  </ol>
                  <p className="mt-2 text-muted">
                    {t("Then write to the bot from the app. The first message gets a pairing code; approve it here or run")}{" "}
                    <code className="rounded bg-surface px-1">nanomuse channels approve &lt;code&gt;</code>
                  </p>
                  {c.console_url && (
                    <a href={c.console_url} target="_blank" rel="noreferrer" className="mt-2 inline-flex items-center gap-1 text-accent underline-offset-2 hover:underline">
                      <ExternalLink size={14} /> {t("Open the console")}
                    </a>
                  )}
                </div>
              )}
            </div>
            <MuseDivider inset={16} />
            <p className="px-4 pt-3 text-[13px] text-muted">{t("Paired chats")}</p>
            {c.paired.length === 0 ? (
              <p className="px-4 pb-3 pt-1 text-[13px] leading-[18px] text-muted">
                {t("Nobody yet. Write to the bot from the app; a pairing code comes back, approve it here.")}
              </p>
            ) : (
              c.paired.map((p) => (
                <div key={p.sender_id} className="flex items-center">
                  <div className="min-w-0 flex-1">
                    <MuseSwitchRow
                      label={p.sender_name || p.sender_id}
                      value={t("Deliver here")}
                      checked={p.deliver}
                      onChange={(v) => void run(() => channelsApi.setDeliver(c.name, p.sender_id, v))}
                    />
                  </div>
                  <button
                    type="button"
                    className="mr-3 rounded-full p-2 text-muted hover:bg-surface-2"
                    aria-label={t("Remove")}
                    onClick={() => {
                      if (!window.confirm(t("Remove {name}? They will need a new pairing code.", { name: p.sender_name || p.sender_id }))) return;
                      void run(() => channelsApi.removeChat(c.name, p.sender_id));
                    }}
                  >
                    <Trash2 size={16} />
                  </button>
                </div>
              ))
            )}
          </>
        )}
      </MuseCard>
      {(open || c.enabled) && c.paired.length > 0 && (
        <MuseCaption>
          {t("Chats with “deliver here” on also get what the Muse does on its own: check-ins, reminders, the results of background work, and the questions it needs answered.")}
        </MuseCaption>
      )}
    </>
  );
}

function FieldInput({
  f,
  value,
  onChange,
  onClear,
}: {
  f: ChannelField;
  value: string | undefined;
  onChange: (v: string) => void;
  onClear: () => void;
}) {
  const t = useT();
  if (f.kind === "choice") {
    return (
      <label className="block">
        <span className="mb-1 block text-[13px] text-muted">{f.label}</span>
        <select className={inputCls} value={value ?? String(f.value ?? f.default)} onChange={(e) => onChange(e.target.value)}>
          {f.choices.map((ch) => (
            <option key={ch} value={ch}>
              {ch}
            </option>
          ))}
        </select>
        {f.help && <span className="mt-1 block text-[12.5px] text-muted">{f.help}</span>}
      </label>
    );
  }
  const secret = f.kind === "secret";
  return (
    <label className="block">
      <span className="mb-1 block text-[13px] text-muted">
        {f.label}
        {f.required ? " *" : ""}
      </span>
      <div className="flex gap-2">
        <input
          type={secret ? "password" : "text"}
          autoComplete="off"
          className={inputCls}
          value={value ?? (secret ? "" : String(f.value ?? ""))}
          placeholder={secret && f.has_value ? t("Set; leave blank to keep") : f.help}
          onChange={(e) => onChange(e.target.value)}
        />
        {secret && f.has_value && (
          <button type="button" className={cx(secondaryBtn, "shrink-0 px-3")} onClick={onClear} title={t("Clear the saved value")} aria-label={t("Clear the saved value")}>
            <X size={14} />
          </button>
        )}
      </div>
      {f.help && !secret && <span className="mt-1 block text-[12.5px] text-muted">{f.help}</span>}
    </label>
  );
}
