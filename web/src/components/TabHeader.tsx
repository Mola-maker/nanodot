import { Menu, MoreHorizontal } from "lucide-react";
import { useEffect, useRef, useState, type ReactNode } from "react";
import { useT } from "../i18n";
import { useStore, type Tab } from "../store";
import { MuseHeader, MusePageTitle, MuseRoundButton } from "./MuseHeader";
import { MuseSheet } from "../screens/MuseSheet";

/**
 * The big-face header the Android app puts on the Feed, Ideas, Goals and Library tabs: the
 * face and the name tag with what the agent is doing, Muse's round hamburger (the chats
 * drawer) at the left, "•••" at the right — a menu of the page's own actions, then System
 * files and Settings, the way the phone's reads — and the big left-aligned page title under
 * it. `trailing` replaces the menu (the Feed puts its sliders there).
 */
export function TabHeader({
  title,
  actions = [],
  trailing,
  children,
}: {
  title: string;
  /** the page's own entries at the top of the "•••" menu */
  actions?: Array<{ label: string; onClick: () => void }>;
  trailing?: ReactNode;
  /** what sits beside the title (a refresh, a +) */
  children?: ReactNode;
}) {
  const { state, setDrawer } = useStore();
  const t = useT();
  const [activityOpen, setActivityOpen] = useState(false);

  const name = state.profile?.name || "Dot";
  const waiting = state.pendingApprovals.length;
  const statusLine =
    waiting > 0 ? (waiting > 1 ? t("{n} approvals waiting for you", { n: waiting }) : t("1 approval waiting for you"))
    : state.status.state === "working" ? state.status.detail || t("On it")
    : state.status.state === "waiting" ? t("Waiting for you")
    : null;

  return (
    <>
      <MuseHeader
        profile={state.profile}
        status={state.status}
        name={name}
        statusLine={statusLine}
        statusTone={statusLine ? "accent" : "muted"}
        spinning={state.status.state === "working" && waiting === 0}
        badge={waiting}
        onAvatar={() => setActivityOpen(true)}
        leading={
          <MuseRoundButton onClick={() => setDrawer(true)} label={t("Chats")} className="wide:invisible">
            <Menu size={22} />
          </MuseRoundButton>
        }
        trailing={trailing ?? <MoreMenu actions={actions} />}
      />
      {(title || children) && (
        <div className="flex shrink-0 items-center gap-3 pr-5">
          <MusePageTitle className="flex-1">{title}</MusePageTitle>
          {children}
        </div>
      )}
      <MuseSheet open={activityOpen} onClose={() => setActivityOpen(false)} />
    </>
  );
}

/**
 * The "•••" disc and its dropdown, as the phone's tab headers have it: the page's own
 * entries first, then Memory and Settings.
 */
export function MoreMenu({ actions = [] }: { actions?: Array<{ label: string; onClick: () => void }> }) {
  const { setTab } = useStore();
  const t = useT();
  const [menu, setMenu] = useState(false);
  const menuRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!menu) return;
    const onDown = (e: PointerEvent) => {
      if (!menuRef.current?.contains(e.target as Node)) setMenu(false);
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") setMenu(false);
    };
    window.addEventListener("pointerdown", onDown);
    window.addEventListener("keydown", onKey);
    return () => {
      window.removeEventListener("pointerdown", onDown);
      window.removeEventListener("keydown", onKey);
    };
  }, [menu]);
  const go = (tab: Tab) => {
    setMenu(false);
    setTab(tab);
  };
  const entries: Array<{ label: string; onClick: () => void }> = [
    ...actions.map((a) => ({ label: a.label, onClick: () => { setMenu(false); a.onClick(); } })),
    { label: t("Memory"), onClick: () => go("memory") },
    { label: t("Settings"), onClick: () => go("you") },
  ];
  return (
    <div ref={menuRef} className="relative">
      <MuseRoundButton onClick={() => setMenu((m) => !m)} label={t("More")} expanded={menu}>
        <MoreHorizontal size={22} />
      </MuseRoundButton>
      {menu && (
        <ul role="menu" className="absolute right-0 top-12 z-30 min-w-[180px] overflow-hidden rounded-2xl border border-border bg-surface py-1 shadow-xl">
          {entries.map((e) => (
            <li key={e.label}>
              <button type="button" role="menuitem" onClick={e.onClick} className="w-full px-4 py-2.5 text-left text-[14.5px] hover:bg-surface-2">
                {e.label}
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
