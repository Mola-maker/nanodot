import { ArrowDown, ArrowLeft, ArrowRight, ArrowUp, Check, CornerDownLeft, Globe, Hand, Loader2, RefreshCw } from "lucide-react";
import { useCallback, useEffect, useRef, useState } from "react";
import { androidApp, nativeTakeOver } from "../android";
import { api, frameUrl } from "../api";
import { useT } from "../i18n";
import { useStore } from "../store";
import type { BrowserEvent } from "../types";
import { cx } from "../util";
import { HoldStrip, holdLine } from "./Cards";
import { Sheet } from "./Sheet";

/** How often the picture is refreshed while the person has the page (ms). */
const LIVE_POLL_MS = 1000;

/**
 * Watch the agent's browser, or take over. The frame is the latest one the server has
 * for this card; it refreshes as the card is updated over the WebSocket, and once a
 * second while you have the page.
 *
 * Taking over puts a *hold* on (contract C1): the agent's next browser action waits until
 * you press Done. In that state a tap on the picture clicks the page there, the field
 * types, Enter presses Enter, the arrows scroll, Back goes back and the URL bar opens a
 * page — that is how you sign in when the agent stops at a login form. When the agent
 * hands the page to you itself ("Your turn — sign in to Gmail") the same controls are on
 * and the same Done gives it back.
 */
export function BrowserViewer({ thread, eventId, onClose }: { thread: string; eventId: string; onClose: () => void }) {
  const { state, toast } = useStore();
  const t = useT();
  const event = (state.events[thread] ?? []).find((e) => e.id === eventId) as BrowserEvent | undefined;
  // the hold for this chat's browser, if one is on: by you (Take over) or by the agent (hand_over)
  const hold = state.holds.find((h) => h.thread === thread && h.tool === "browser");
  const [pending, setPending] = useState<"on" | "off" | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [text, setText] = useState("");
  const [url, setUrl] = useState("");
  const [gone, setGone] = useState(false);
  const imgRef = useRef<HTMLImageElement>(null);
  const control = pending ? pending === "on" : !!hold;

  useEffect(() => setGone(false), [event?.frame]);
  // the server's word wins once it arrives
  useEffect(() => setPending(null), [hold?.id, hold?.status]);

  // the phone's own browser: the app shows the real page in a sheet; when the user is done
  // it says so and the server takes a fresh look
  const inApp = event?.backend === "device" && nativeTakeOver();
  useEffect(() => {
    if (!inApp) return;
    const onBack = (e: Event) => {
      const detail = (e as CustomEvent<{ thread?: string }>).detail;
      if (detail?.thread && detail.thread !== thread) return;
      void api.browserControl(thread, { action: "handed_back" }).catch((err: Error) => toast(err.message));
    };
    window.addEventListener("nanomuse:browser-handed-back", onBack);
    return () => window.removeEventListener("nanomuse:browser-handed-back", onBack);
  }, [inApp, thread, toast]);

  const act = useCallback(
    async (body: Parameters<typeof api.browserControl>[1]) => {
      setBusy(body.action);
      try {
        await api.browserControl(thread, body);
      } catch (e) {
        toast((e as Error).message);
      } finally {
        setBusy(null);
      }
    },
    [thread, toast],
  );

  const takeOver = () => {
    if (inApp) {
      androidApp()?.takeOverBrowser?.(thread);
      void api.browserControl(thread, { action: "take_over" }).catch(() => undefined);
      return;
    }
    setPending("on");
    void act({ action: "take_over" });
  };

  const done = () => {
    setPending("off");
    void act({ action: "handed_back" });
  };

  // while you have the page, the picture follows what you do: a fresh look every second
  // (a page that is still loading, a redirect after a sign-in, a code that arrived)
  useEffect(() => {
    if (!control || inApp || !event || event.status !== "live") return;
    const timer = window.setInterval(() => {
      if (document.hidden) return;
      void api.browserControl(thread, { action: "look" }).catch(() => undefined);
    }, LIVE_POLL_MS);
    return () => window.clearInterval(timer);
  }, [control, inApp, thread, event]);

  const onTap = (e: React.MouseEvent<HTMLImageElement>) => {
    if (!control || busy) return;
    const rect = e.currentTarget.getBoundingClientRect();
    const x = (e.clientX - rect.left) / rect.width;
    const y = (e.clientY - rect.top) / rect.height;
    void act({ action: "click", x: Math.min(Math.max(x, 0), 1), y: Math.min(Math.max(y, 0), 1) });
  };

  const onWheel = (e: React.WheelEvent<HTMLImageElement>) => {
    if (!control || busy || Math.abs(e.deltaY) < 8) return;
    void act({ action: "scroll", dy: Math.max(-2000, Math.min(2000, Math.round(e.deltaY * 2))) });
  };

  if (!event) return null;
  let host = event.url;
  try {
    host = new URL(event.url).host;
  } catch {
    /* raw */
  }
  const live = event.status === "live";
  const name = state.profile?.name ?? "Dot";
  const agentState = hold
    ? t("{name} is waiting for you", { name })
    : state.status.state !== "idle" && state.status.thread === thread
      ? t("{name} is working", { name })
      : event.action;

  return (
    <Sheet
      open
      onClose={onClose}
      title={
        <div className="flex items-center gap-2 min-w-0">
          <Globe size={17} className="text-accent shrink-0" />
          <span className="truncate">{event.title || host}</span>
          {live && (
            <span className="ml-1 flex items-center gap-1 rounded-full bg-rose-500/12 px-2 py-0.5 text-[10.5px] font-semibold text-rose-500">
              <span className="h-1.5 w-1.5 rounded-full bg-rose-500 animate-pulse" /> {t("LIVE")}
            </span>
          )}
        </div>
      }
      footer={
        control ? (
          <div className="space-y-2">
            <form
              className="flex items-center gap-2"
              onSubmit={(e) => {
                e.preventDefault();
                if (!text) return;
                void act({ action: "type", text }).then(() => setText(""));
              }}
            >
              <input
                value={text}
                onChange={(e) => setText(e.target.value)}
                placeholder={t("Type into the focused field…")}
                className="flex-1 rounded-2xl bg-surface-2 px-3.5 py-2.5 text-[14px] outline-none"
                autoComplete="off"
                autoCapitalize="off"
              />
              <button type="submit" disabled={!text || !!busy} aria-label={t("Type")} className="rounded-full bg-accent p-2.5 text-white disabled:opacity-40">
                {busy === "type" ? <Loader2 size={16} className="animate-spin" /> : <ArrowRight size={16} />}
              </button>
              <button
                type="button"
                onClick={() => void act({ action: "key", key: "Enter" })}
                disabled={!!busy}
                aria-label={t("Press Enter")}
                className="rounded-full bg-surface-2 p-2.5 text-fg disabled:opacity-40"
              >
                <CornerDownLeft size={16} />
              </button>
            </form>
            <form
              className="flex items-center gap-2"
              onSubmit={(e) => {
                e.preventDefault();
                if (!url) return;
                void act({ action: "navigate", url }).then(() => setUrl(""));
              }}
            >
              <button
                type="button"
                onClick={() => void act({ action: "back" })}
                disabled={!!busy}
                aria-label={t("Back")}
                className="rounded-full bg-surface-2 p-2 text-fg disabled:opacity-40"
              >
                <ArrowLeft size={15} />
              </button>
              <input
                value={url}
                onChange={(e) => setUrl(e.target.value)}
                placeholder={t("Open a URL…")}
                inputMode="url"
                autoCapitalize="off"
                className="flex-1 min-w-0 rounded-2xl bg-surface-2 px-3.5 py-2 text-[13px] outline-none"
              />
              <button type="submit" disabled={!url || !!busy} className="rounded-2xl bg-surface-2 px-3 py-2 text-[13px] font-medium disabled:opacity-40">
                {t("Go")}
              </button>
              <button
                type="button"
                onClick={() => void act({ action: "scroll", dy: -600 })}
                disabled={!!busy}
                aria-label={t("Scroll up")}
                className="rounded-full bg-surface-2 p-2 text-fg disabled:opacity-40"
              >
                <ArrowUp size={15} />
              </button>
              <button
                type="button"
                onClick={() => void act({ action: "scroll", dy: 600 })}
                disabled={!!busy}
                aria-label={t("Scroll down")}
                className="rounded-full bg-surface-2 p-2 text-fg disabled:opacity-40"
              >
                <ArrowDown size={15} />
              </button>
            </form>
            <div className="flex items-center justify-between gap-2">
              <span className="min-w-0 truncate text-[12px] text-muted">{t("Tap the page to click. If the browser was closed, open a URL first.")}</span>
              <button
                type="button"
                onClick={done}
                disabled={busy === "handed_back"}
                className="flex shrink-0 items-center gap-1.5 whitespace-nowrap rounded-full bg-fg px-3.5 py-1.5 text-[13px] font-semibold text-bg disabled:opacity-60"
              >
                {busy === "handed_back" ? <Loader2 size={14} className="animate-spin" /> : <Check size={14} />} {t("Done")}
              </button>
            </div>
          </div>
        ) : (
          <div className="flex items-center justify-between gap-2">
            <div className="min-w-0 text-[12.5px] text-muted truncate">
              {agentState} · {host}
            </div>
            <div className="flex items-center gap-2 shrink-0">
              <button
                type="button"
                onClick={() => void act({ action: "look" })}
                disabled={!!busy}
                aria-label={t("Refresh")}
                className="rounded-full bg-surface-2 p-2 text-muted disabled:opacity-40"
              >
                {busy === "look" ? <Loader2 size={15} className="animate-spin" /> : <RefreshCw size={15} />}
              </button>
              <button
                type="button"
                onClick={takeOver}
                disabled={pending === "on"}
                className="flex items-center gap-1.5 rounded-full bg-accent px-3.5 py-2 text-[13px] font-semibold text-white disabled:opacity-60"
              >
                <Hand size={14} /> {t("Take over")}
              </button>
            </div>
          </div>
        )
      }
    >
      {hold && hold.by === "agent" && (
        <div className="mb-2">
          <HoldStrip hold={hold} compact />
        </div>
      )}
      <div className={cx("relative overflow-hidden rounded-2xl border border-border bg-surface-2", control && "ring-2 ring-accent")}>
        {gone ? (
          <div className="flex aspect-[16/10] flex-col items-center justify-center gap-1 text-muted">
            <Globe size={24} />
            <span className="text-[12.5px]">{t("This frame is no longer available.")}</span>
            <button type="button" onClick={() => void act({ action: "look" })} className="text-accent text-[13px] font-medium">
              {t("Look again")}
            </button>
          </div>
        ) : (
          <img
            ref={imgRef}
            key={event.frame}
            src={frameUrl(thread, event.frame)}
            alt={event.title || host}
            onError={() => setGone(true)}
            onClick={onTap}
            onWheel={onWheel}
            draggable={false}
            className={cx("block w-full select-none", control ? "cursor-crosshair" : "")}
          />
        )}
        {busy && busy !== "look" && (
          <div className="absolute inset-0 flex items-center justify-center bg-black/20">
            <Loader2 size={26} className="animate-spin text-white" />
          </div>
        )}
      </div>
      <div className="mt-2 flex items-center justify-between text-[12px] text-muted">
        <span className="truncate">{event.url}</span>
        <span className="shrink-0 ml-2">{event.frames === 1 ? t("1 frame") : t("{n} frames", { n: event.frames })}</span>
      </div>
      {control && (
        <div className="mt-3 rounded-2xl bg-accent/8 px-3.5 py-2.5 text-[13px] leading-snug">
          <div className="font-medium">{hold && hold.by === "agent" ? holdLine(hold, t) : t("You have the page. Press Done when you are finished.")}</div>
          <div className="mt-1 text-muted">
            {t("{name} waits meanwhile, then looks at the page as you left it and carries on. Passwords you type here go to the website, never to the model.", { name })}
          </div>
        </div>
      )}
    </Sheet>
  );
}
