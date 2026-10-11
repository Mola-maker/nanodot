import { FileText, Home, MessageSquare, Monitor, MonitorSmartphone, Search, Settings, Smartphone, SquarePen, Terminal, Trash2, X } from "lucide-react";
import { useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { api } from "../api";
import { useStore } from "../store";
import type { HubDevice, ThreadMeta } from "../types";
import { intlLocale, useT } from "../i18n";
import { cx } from "../util";
import { useFocusTrap } from "./useFocusTrap";

/**
 * The chats drawer the Android app slides in from the left: the agent's name, the main chat,
 * Devices and (when a computer with one is online) Coding agents as rows, then the side chats
 * under their own heading, and settings · search · new along the bottom. Opened by the round
 * hamburger on the chat and the four tabs; `state.drawer` holds whether it is showing.
 */
export function ChatsDrawer() {
  const { state, setDrawer, setTab, openThread, toast, dispatch } = useStore();
  const t = useT();
  const open = state.drawer;
  const [query, setQuery] = useState("");
  const [confirm, setConfirm] = useState<string | null>(null);
  const panel = useRef<HTMLElement>(null);
  useFocusTrap(panel, open);

  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && setDrawer(false);
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [open, setDrawer]);
  useEffect(() => { if (open) requestAnimationFrame(() => panel.current?.querySelector<HTMLInputElement>("input")?.focus()); }, [open]);
  useEffect(() => {
    if (!open) {
      setQuery("");
      setConfirm(null);
    }
  }, [open]);

  const name = state.profile?.name || "Dot";
  const side = useMemo(() => {
    const q = query.trim().toLowerCase();
    return state.threads
      .filter((th) => th.id !== "main")
      .filter((th) => !q || (th.title || "").toLowerCase().includes(q))
      .sort((a, b) => (b.updated_at || "").localeCompare(a.updated_at || ""));
  }, [state.threads, query]);
  const others: HubDevice[] = (state.hub?.devices ?? []).filter((d) => !d.this && d.kind !== "web");
  const othersOnline = others.filter((d) => d.online).length;
  const hubOn = state.hub?.state === "connected";
  const computersOnline = others.some((d) => d.online && d.kind === "computer");
  const chatHere = state.tab === "chat";

  const pick = (thread: string) => {
    openThread(thread);
    setTab("chat");
    setDrawer(false);
  };
  const go = (tab: Parameters<typeof setTab>[0]) => {
    setTab(tab);
    setDrawer(false);
  };
  const create = async () => {
    try {
      const created = await api.createThread(t("New chat"));
      dispatch({ type: "ws", msg: { kind: "thread", thread: created } });
      pick(created.id);
    } catch (e) {
      toast((e as Error).message);
    }
  };
  const remove = async (id: string) => {
    try {
      await api.deleteThread(id);
      dispatch({ type: "ws", msg: { kind: "thread_deleted", thread: id } });
      if (state.activeThread === id) openThread("main");
    } catch (e) {
      toast((e as Error).message);
    } finally {
      setConfirm(null);
    }
  };
  const kindOf = (id: string) => state.hub?.devices.find((d) => d.id === id)?.kind ?? "phone";

  const devicesLine = !hubOn ? t("Off") : othersOnline === 0 ? t("Only this one") : t("{n} online", { n: othersOnline });

  if (!open) return null;
  return (
    <div className="fixed inset-0 z-50">
      <div className="rise absolute inset-0 bg-black/40" onClick={() => setDrawer(false)} />
      <nav ref={panel} role="dialog" aria-modal="true" aria-label={t("Chats")} className="drawer-in safe-top safe-bottom absolute inset-y-0 left-0 flex w-[82vw] max-w-[320px] flex-col bg-bg shadow-2xl">
        <div className="px-5 pt-5 pb-2 text-[1.5rem] font-bold leading-7 tracking-tight">{name}</div>

        {/* the main chat, Devices and the coding agents: rows, one tap away like a chat */}
        <Row icon={<Home size={20} />} label={t("Main chat")} selected={chatHere && state.activeThread === "main"} onClick={() => pick("main")} />
        <Row icon={<MonitorSmartphone size={20} />} label={t("Devices")} detail={devicesLine} selected={state.tab === "devices"} onClick={() => go("devices")} />
        {computersOnline && (
          <Row icon={<Terminal size={20} />} label={t("Coding agents")} selected={state.tab === "coding"} onClick={() => go("coding")} />
        )}

        <div className="mt-3 px-5 pb-1">
          <span className="text-[0.875rem] font-medium text-muted">{t("Side chats")}</span>
        </div>
        {side.length === 0 && !query ? (
          <div className="flex flex-1 flex-col items-center justify-center px-8 text-center">
            <MessageSquare size={28} className="mb-3 text-muted/70" />
            <div className="text-[1.0625rem] font-semibold">{t("Start a side chat")}</div>
            <div className="mt-1 text-[0.8125rem] leading-snug text-muted">{t("Side chats are an optional way to keep conversations organised by topic.")}</div>
          </div>
        ) : (
          <ul className="min-h-0 flex-1 overflow-y-auto py-1">
            {side.map((th) => (
              <SideRow
                key={th.id}
                thread={th}
                kind={th.device ? kindOf(th.device) : undefined}
                selected={chatHere && state.activeThread === th.id}
                confirming={confirm === th.id}
                onPick={() => pick(th.id)}
                onAskDelete={() => setConfirm(th.id)}
                onDelete={() => void remove(th.id)}
                onKeep={() => setConfirm(null)}
              />
            ))}
            {side.length === 0 && <li className="px-5 py-3 text-[0.8125rem] text-muted">{t("No chats match.")}</li>}
          </ul>
        )}

        {/* bottom strip: settings · search · new */}
        <div className="flex items-center gap-2 border-t border-border px-3 pt-2 pb-2">
          <button type="button" onClick={() => go("you")} aria-label={t("Settings")} className="flex h-10 w-10 items-center justify-center rounded-full text-fg hover:bg-surface-2">
            <Settings size={20} />
          </button>
          <button type="button" onClick={() => go("memory")} aria-label={t("Memory")} className="-ml-1 flex h-10 w-10 items-center justify-center rounded-full text-fg hover:bg-surface-2">
            <FileText size={20} />
          </button>
          <label className="flex min-w-0 flex-1 items-center gap-2 rounded-full bg-surface-2 px-3 py-2">
            <Search size={16} className="shrink-0 text-muted" />
            <input
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder={t("Search chats")} aria-label={t("Search chats")}
              className="min-w-0 flex-1 bg-transparent text-[0.9375rem] outline-none placeholder:text-muted"
            />
            {query && (
              <button type="button" onClick={() => setQuery("")} aria-label={t("Clear")} className="text-muted">
                <X size={14} />
              </button>
            )}
          </label>
          <button type="button" onClick={() => void create()} aria-label={t("New side chat")} className="flex h-10 w-10 items-center justify-center rounded-full text-fg hover:bg-surface-2">
            <SquarePen size={20} />
          </button>
        </div>
      </nav>
    </div>
  );
}

function Row({ icon, label, detail, selected, onClick }: { icon: ReactNode; label: string; detail?: string; selected: boolean; onClick: () => void }) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={cx("mx-2 flex items-center gap-3 rounded-2xl px-3 py-2.5 text-left", selected ? "bg-surface-2" : "hover:bg-surface-2/60")}
    >
      <span className="text-fg">{icon}</span>
      <span className="min-w-0 flex-1 truncate text-[1rem]">{label}</span>
      {detail && <span className="shrink-0 text-[0.8125rem] text-muted">{detail}</span>}
    </button>
  );
}

function SideRow({
  thread,
  kind,
  selected,
  confirming,
  onPick,
  onAskDelete,
  onDelete,
  onKeep,
}: {
  thread: ThreadMeta;
  kind?: string;
  selected: boolean;
  confirming: boolean;
  onPick: () => void;
  onAskDelete: () => void;
  onDelete: () => void;
  onKeep: () => void;
}) {
  const t = useT();
  if (confirming) {
    return (
      <li className="mx-2 flex items-center gap-2 rounded-2xl bg-surface-2 px-3 py-2">
        <span className="min-w-0 flex-1 truncate text-[0.84375rem]">{t("Delete this side chat and its history?")}</span>
        <button type="button" onClick={onDelete} className="rounded-full bg-rose-500 px-3 py-1 text-[0.78125rem] font-semibold text-white">
          {t("Delete")}
        </button>
        <button type="button" onClick={onKeep} className="rounded-full px-2 py-1 text-[0.78125rem] text-muted">
          {t("Keep")}
        </button>
      </li>
    );
  }
  return (
    <li className={cx("group mx-2 flex items-center gap-2 rounded-2xl px-3", selected ? "bg-surface-2" : "hover:bg-surface-2/60")}>
      <button type="button" onClick={onPick} className="flex min-w-0 flex-1 items-center gap-2 py-2.5 text-left">
        {thread.device ? (
          <span className="shrink-0 text-accent">{kind === "computer" ? <Monitor size={15} /> : <Smartphone size={15} />}</span>
        ) : thread.remote_from ? (
          <MonitorSmartphone size={15} className="shrink-0 text-muted" />
        ) : null}
        {/* a chat started on another device of the account is a normal chat here (C8): no
            badge in the list — the turns written elsewhere carry their caption instead */}
        <span className="min-w-0 flex-1 truncate text-[0.9375rem]">{thread.title || t("New chat")}</span>
        {thread.busy && <span className="h-1.5 w-1.5 shrink-0 animate-pulse rounded-full bg-amber-400" />}
        <span className="shrink-0 text-[0.75rem] text-muted">{relativeDay(thread.updated_at)}</span>
      </button>
      <button type="button" aria-label={t("Delete")} onClick={onAskDelete} className="p-1.5 text-muted/70 opacity-0 hover:text-rose-500 focus:opacity-100 group-hover:opacity-100">
        <Trash2 size={15} />
      </button>
    </li>
  );
}

/** The Android drawer's time column: the clock today, the weekday within a week, the date after. */
export function relativeDay(ts: string | undefined, now = new Date()): string {
  if (!ts) return "";
  const d = new Date(ts);
  if (Number.isNaN(d.getTime())) return "";
  const diff = now.getTime() - d.getTime();
  if (d.toDateString() === now.toDateString()) return d.toLocaleTimeString(intlLocale(), { hour: "2-digit", minute: "2-digit" });
  if (diff < 7 * 24 * 3600_000) return d.toLocaleDateString(intlLocale(), { weekday: "short" });
  return d.toLocaleDateString(intlLocale(), { month: "short", day: "numeric" });
}
