import { MessageCircle, MonitorSmartphone, SlidersHorizontal, SquareCheckBig, WifiOff } from "lucide-react";
import { lazy, Suspense, useEffect, useState, type ReactNode } from "react";
import { DotStatus } from "./DotStatus";
import { api, setToken } from "./api";
import { BrandMark } from "./components/BrandMark";
import { DesktopRemoteHint } from "./components/DesktopRemoteHint";
import { ChatsDrawer } from "./components/ChatsDrawer";
import { FileViewer } from "./components/FileViewer";
import { FirstSignInSteps, useFirstSignIn } from "./components/FirstSignInSteps";
import { desktopBridge } from "./desktop";
import { Sidebar } from "./components/Sidebar";
import { ChatScreen } from "./screens/ChatScreen";
import { FeedScreen } from "./screens/FeedScreen";
import { SignInGate } from "./screens/SignInGate";
import { signInDoorNeeded } from "./gate";
import { useStore, type Tab } from "./store";
import { PRIMARY_NAVIGATION, useT } from "./i18n";
import { cx } from "./util";

// The chat and the feed are what opens first; every other screen arrives as its own chunk
// the first time it is shown, so the first paint does not carry the provider form or the
// coding console.
const AccountScreen = lazy(() => import("./screens/AccountScreen").then((m) => ({ default: m.AccountScreen })));
const AvatarStudioScreen = lazy(() => import("./screens/AvatarStudioScreen").then((m) => ({ default: m.AvatarStudioScreen })));
const ChannelsScreen = lazy(() => import("./screens/ChannelsScreen").then((m) => ({ default: m.ChannelsScreen })));
const CodingScreen = lazy(() => import("./screens/CodingScreen").then((m) => ({ default: m.CodingScreen })));
const ConnectionsScreen = lazy(() => import("./screens/ConnectionsScreen").then((m) => ({ default: m.ConnectionsScreen })));
const DevicesScreen = lazy(() => import("./screens/DevicesScreen").then((m) => ({ default: m.DevicesScreen })));
const GoalsScreen = lazy(() => import("./screens/GoalsScreen").then((m) => ({ default: m.GoalsScreen })));
const IdeasScreen = lazy(() => import("./screens/IdeasScreen").then((m) => ({ default: m.IdeasScreen })));
const LibraryScreen = lazy(() => import("./screens/LibraryScreen").then((m) => ({ default: m.LibraryScreen })));
const MemoryScreen = lazy(() => import("./screens/MemoryScreen").then((m) => ({ default: m.MemoryScreen })));
const Onboarding = lazy(() => import("./screens/Onboarding").then((m) => ({ default: m.Onboarding })));
const SettingsScreen = lazy(() => import("./screens/SettingsScreen").then((m) => ({ default: m.SettingsScreen })));
const SkillsScreen = lazy(() => import("./screens/SkillsScreen").then((m) => ({ default: m.SkillsScreen })));

function Loading() {
  const t = useT();
  return <DotStatus state="loading" message={t("Loading…")} />;
}

/** Four destinations use a filled glyph and a text label for the selected state. */
const glyph = (active: boolean) => ({ size: 26, strokeWidth: active ? 2 : 1.7, fill: active ? "currentColor" : "none", fillOpacity: active ? 0.18 : 0 });
const TASK_ROOMS = ["feed", "ideas", "goals", "library"] as const;
const SETTINGS_ROOMS = ["you", "memory", "connections", "channels", "skills", "account", "coding", "avatar"] as const;
const TABS: Array<{ id: Tab; label: string; icon: (active: boolean) => ReactNode }> = [
  { id: "chat", label: PRIMARY_NAVIGATION[0], icon: (a) => <MessageCircle {...glyph(a)} /> },
  { id: "feed", label: PRIMARY_NAVIGATION[1], icon: (a) => <SquareCheckBig {...glyph(a)} /> },
  { id: "devices", label: PRIMARY_NAVIGATION[2], icon: (a) => <MonitorSmartphone {...glyph(a)} /> },
  { id: "you", label: PRIMARY_NAVIGATION[3], icon: (a) => <SlidersHorizontal {...glyph(a)} /> },
];

export default function App() {
  const { state, setTab, setDrawer, openFile } = useStore();
  const firstSignIn = useFirstSignIn();
  const tasksActive = (TASK_ROOMS as readonly string[]).includes(state.tab);
  const settingsActive = (SETTINGS_ROOMS as readonly string[]).includes(state.tab);
  useEffect(() => {
    const key = (event: KeyboardEvent) => {
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "k") {
        event.preventDefault();
        setTab("chat");
        if (!window.matchMedia("(min-width: 64rem)").matches) setDrawer(true);
        window.dispatchEvent(new Event("nanomuse:search-chats"));
      }
    };
    window.addEventListener("keydown", key);
    return () => window.removeEventListener("keydown", key);
  }, [setTab, setDrawer]);
  const t = useT();

  // The document title follows the agent's name.
  useEffect(() => {
    const name = state.profile?.name?.trim();
    document.title = name && name.toLowerCase() !== "nanomuse" ? `${name} · Nanodot` : "Nanodot";
  }, [state.profile?.name]);

  // the desktop's quick-chat shortcut: to the chat, with the composer focused
  useEffect(() => {
    const off = desktopBridge()?.onQuickChat?.(() => {
      setTab("chat");
      requestAnimationFrame(() => window.dispatchEvent(new Event("nanomuse:quick-chat")));
    });
    return () => {
      if (typeof off === "function") off();
    };
  }, [setTab]);

  // `#devices` etc. opens a section straight away — the desktop tray menu links here.
  useEffect(() => {
    const jump = () => {
      const tab = window.location.hash.slice(1) as Tab;
      if (tab && (TABS.some((x) => x.id === tab) || (TASK_ROOMS as readonly string[]).includes(tab))) setTab(tab);
      else if (tab === "devices" || tab === "you" || tab === "memory" || tab === "connections" || tab === "channels" || tab === "skills" || tab === "account" || tab === "coding" || tab === "avatar") setTab(tab);
    };
    jump();
    window.addEventListener("hashchange", jump);
    return () => window.removeEventListener("hashchange", jump);
  }, [setTab]);

  // A freshly created account is owed two short steps (a password, which model
  // answers); they float over whatever comes next — setup or the chat.
  const firstSteps = firstSignIn && state.hub?.account.signed_in ? <FirstSignInSteps /> : null;

  // Lite (inside the showcase's phone): the runtime there is set up by its host, so the
  // first-run setup has nothing to ask; mark it done and go straight to the chat.
  const liteSetup = state.lite && state.settings !== null && !state.settings.onboarded;
  useEffect(() => {
    if (liteSetup) void api.onboarded(true).catch(() => undefined);
  }, [liteSetup]);

  if (state.authError) return <TokenGate />;
  // Nothing has arrived from the runtime yet: say so, instead of an empty chat.
  if (!state.loaded) return <Connecting error={state.error} />;
  // The account comes first when nothing else could answer: this runtime asks for one
  // (cloud.required), none is signed in, no model of one's own is ready, and the person has not
  // stepped past the door for a key of their own. The sign-in stays an invitation under Connections.
  if (signInDoorNeeded(state.hub, state.settings, state.signInSkipped)) {
    return <SignInGate />;
  }
  // First run: the server has not seen setup finish and nothing has been said yet.
  if (!state.lite && state.settings && !state.settings.onboarded && !state.onboardingDismissed && !state.threads.some((t) => t.events > 0)) {
    return (
      <Suspense fallback={<Loading />}>
        <Onboarding />
        {firstSteps}
      </Suspense>
    );
  }

  const pendingApprovals = state.pendingApprovals.length;
  // a plan change waiting for your answer is worth a red badge; the count of goals is not
  const proposals = state.goals.filter((g) => g.proposal && g.status !== "cancelled").length;
  const feedUnseen = state.pendingApprovals.filter((a) => a.ts > state.feedSeenAt).length;

  // One column on the phone; on a wide screen (a full browser) and in the desktop app at any
  // width, a sidebar takes over from the tab bar and the chats sheet — same screens either side.
  return (
    <div className={cx("mx-auto flex h-[100dvh] max-w-[760px] flex-col bg-bg sm:border-x sm:border-border", !state.lite && "wide:max-w-none wide:flex-row wide:border-x-0")}>
      {!state.lite && (
        <div className="hidden wide:block wide:h-full">
          <Sidebar />
        </div>
      )}
      <div className="flex min-h-0 min-w-0 flex-1 flex-col">
        {state.gone && (
          <div className="flex items-center justify-center gap-2 bg-surface-2 text-muted text-[0.78125rem] py-1">
            <WifiOff size={14} /> {t(state.lite ? "This demo has ended." : "This Nanodot has stopped. Reload the page once it is running again.")}
          </div>
        )}
        {!state.gone && !state.connected && state.loaded && (
          <DotStatus state="error" message={t("Dot is offline.")} onRetry={() => window.location.reload()} />
        )}
        {!state.lite && <DesktopRemoteHint />}
        {tasksActive && <nav aria-label={t("Tasks")} className="dot-task-rooms">
          {TASK_ROOMS.map((room) => <button key={room} type="button" aria-current={state.tab === room ? "page" : undefined} onClick={() => setTab(room)}>{t(room[0].toUpperCase() + room.slice(1))}</button>)}
        </nav>}
        <main className="mx-auto min-h-0 w-full flex-1 wide:max-w-[900px]">
          <Suspense fallback={<Loading />}>
            <div hidden={state.tab !== "chat"} className="h-full"><ChatScreen /></div>
            {state.tab === "feed" && <FeedScreen />}
            {state.tab === "ideas" && <IdeasScreen />}
            {state.tab === "goals" && <GoalsScreen />}
            {state.tab === "library" && <LibraryScreen />}
            {state.tab === "memory" && <MemoryScreen />}
            {state.tab === "skills" && <SkillsScreen />}
            {state.tab === "connections" && <ConnectionsScreen />}
            {state.tab === "channels" && <ChannelsScreen />}
            {state.tab === "devices" && <DevicesScreen />}
            {state.tab === "you" && <SettingsScreen />}
            {state.tab === "account" && <AccountScreen />}
            {state.tab === "coding" && <CodingScreen />}
            {state.tab === "avatar" && <AvatarStudioScreen />}
          </Suspense>
        </main>
        {/* Compact windows keep every primary destination in reach below the content. */}
        <nav aria-label={t("Main navigation")} className={cx("safe-bottom shrink-0 bg-bg", !state.lite && "wide:hidden")}>
          <ul className="flex h-14 items-center justify-evenly">
            {TABS.map((tab) => {
              const active = tab.id === "feed" ? tasksActive : tab.id === "you" ? settingsActive : state.tab === tab.id;
              const badge = tab.id === "chat" ? pendingApprovals : tab.id === "feed" ? feedUnseen + proposals : 0;
              return (
                <li key={tab.id}>
                  <button
                    type="button"
                    onClick={() => setTab(tab.id)}
                    aria-label={t(tab.label)}
                    aria-current={active ? "page" : undefined}
                    className={cx("relative flex min-h-12 min-w-12 flex-col items-center justify-center rounded-xl transition", active ? "text-fg" : "text-fg/70 hover:text-fg")}
                  >
                    {tab.icon(active)}<span className="text-xs">{t(tab.label)}</span>
                    {badge > 0 && (
                      <span className="absolute right-1 top-1 flex h-[17px] min-w-[17px] items-center justify-center rounded-full bg-rose-500 px-1 text-[0.625rem] font-bold text-white">
                        {badge}
                      </span>
                    )}
                  </button>
                </li>
              );
            })}
          </ul>
        </nav>
      </div>
      <ChatsDrawer />
      <FileViewer path={state.viewer} onClose={() => openFile(null)} />
      {firstSteps}
      {state.toast && (
        <div className="pointer-events-none fixed inset-x-0 bottom-24 z-[70] flex justify-center px-4">
          <div className="rise rounded-2xl bg-fg text-bg px-4 py-2 text-[0.84375rem] shadow-lg max-w-sm text-center">{state.toast}</div>
        </div>
      )}
    </div>
  );
}

/** The first seconds: the socket is on its way, or the runtime cannot be reached at all. */
function Connecting({ error }: { error: string | null }) {
  const t = useT();
  return (
    <div className="mx-auto flex h-[100dvh] max-w-sm flex-col items-center justify-center px-6 text-center">
      {/* the web's splash: the app's mark, no face (the face is the Muse, who is not here yet) */}
      <BrandMark size={72} />
      <DotStatus state={error ? "error" : "loading"} message={error ? `${t("Could not reach your Nanodot.")} ${t(error)}` : t("Connecting to your Nanodot…")} onRetry={error ? () => window.location.reload() : undefined} />
    </div>
  );
}

function TokenGate() {
  const [value, setValue] = useState("");
  const t = useT();
  return (
    <div className="mx-auto flex h-[100dvh] max-w-md flex-col items-center justify-center px-6 text-center">
      <BrandMark size={88} />
      <DotStatus state="error" message={t("401: Access token required.")} />
      <h1 className="mt-4 text-[1.375rem] font-bold">{t("Connect to your Nanodot")}</h1>
      <p className="mt-2 text-[0.875rem] text-muted">
        {t("This app talks to the Nanodot server you run yourself. Scan the QR code printed by")}{" "}
        <code className="rounded bg-surface-2 px-1">nanomuse serve</code>{t(", or paste the access token below.")}
      </p>
      <form
        className="mt-5 w-full flex gap-2"
        onSubmit={(e) => {
          e.preventDefault();
          if (!value.trim()) return;
          setToken(value.trim());
          window.location.reload();
        }}
      >
        <input
          value={value}
          onChange={(e) => setValue(e.target.value)}
          aria-label={t("Access token")}
          placeholder={t("Access token")}
          className="flex-1 rounded-2xl bg-surface-2 px-4 py-2.5 text-[0.9375rem] outline-none focus:ring-2 focus:ring-accent/40"
        />
        <button type="submit" disabled={!value.trim()} className="rounded-2xl bg-accent text-accent-fg px-4 font-medium">
          {t("Connect")}
        </button>
      </form>
    </div>
  );
}
