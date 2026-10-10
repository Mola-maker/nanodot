import {
  ArrowRight,
  Bell,
  CalendarClock,
  CalendarDays,
  FileText,
  Info,
  Loader2,
  Mail,
  MapPin,
  MessageCircle,
  MessageCircleQuestion,
  Moon,
  ShieldAlert,
  SlidersHorizontal,
  Sparkles,
  Webhook,
} from "lucide-react";
import { useEffect, useMemo, useState, type ReactNode } from "react";
import { api } from "../api";
import { Markdown } from "../components/Markdown";
import { MuseRoundButton } from "../components/MuseHeader";
import { Sheet } from "../components/Sheet";
import { TabHeader } from "../components/TabHeader";
import { intlLocale, localLabel, t, useLocale, useT } from "../i18n";
import { useStore } from "../store";
import type { CalendarData, CalendarEvent, FeedItem, FeedPost, FeedPostsData, UpcomingData } from "../types";
import { Toggle } from "../components/Form";
import { cx, readStorage, relativeTime, timeShort, writeStorage } from "../util";

/**
 * The Feed, the way Muse does it: posts written for you from what it knows, steered by
 * your "feed instructions" — and, below them, what happened while you were away: replies
 * from background passes, files it made, cards still waiting for you.
 */
export function FeedScreen() {
  const { state, send, openThread, openFile, markFeedSeen, setTab, toast } = useStore();
  const [items, setItems] = useState<FeedItem[] | null>(null);
  const [posts, setPosts] = useState<FeedPostsData | null>(null);
  const [upcoming, setUpcoming] = useState<UpcomingData | null>(null);
  const [calendar, setCalendar] = useState<CalendarData | null>(null);
  const name = state.profile?.name ?? "Dot";
  const t = useT();
  const locale = useLocale();

  useEffect(() => {
    let alive = true;
    api.feed(80)
      .then((d) => alive && setItems(d))
      .catch((e: Error) => toast(e.message));
    api.upcoming()
      .then((d) => alive && setUpcoming(d))
      .catch(() => undefined);
    api.feedPosts()
      .then((d) => alive && setPosts(d))
      .catch(() => undefined);
    return () => {
      alive = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [state.feedVersion, state.remindersVersion]);

  // the day's events, when a calendar is connected (a feed refresh pushes a new version)
  useEffect(() => {
    let alive = true;
    api.calendar()
      .then((d) => alive && setCalendar(d))
      .catch(() => undefined);
    return () => {
      alive = false;
    };
  }, [state.calendarVersion, state.connectionsVersion]);

  // Looking at the feed marks it read — the newest item's time is the watermark.
  useEffect(() => {
    if (items && items.length && items[0].ts > state.feedSeenAt) markFeedSeen(items[0].ts);
  }, [items, state.feedSeenAt, markFeedSeen]);

  // eslint-disable-next-line react-hooks/exhaustive-deps
  const groups = useMemo(() => groupByDay(items ?? []), [items, locale]);

  const [settingsOpen, setSettingsOpen] = useState(false);
  const [infoPost, setInfoPost] = useState<FeedPost | null>(null);
  const [introAck, setIntroAck] = useState(() => readStorage(INTRO_ACK_KEY) === "1");
  const [writing, setWriting] = useState(false);
  const ackIntro = () => {
    writeStorage(INTRO_ACK_KEY, "1");
    setIntroAck(true);
  };
  const writeNow = async () => {
    setWriting(true);
    try {
      const d = await api.refreshFeedPosts();
      setPosts(d);
      if (d.error) toast(t("Could not write posts: {error}", { error: d.error }));
    } catch (e) {
      toast((e as Error).message);
    } finally {
      setWriting(false);
    }
  };
  /** "Discuss" on a post: the follow-up it suggested, or the post itself, into the main chat. */
  const discuss = (p: FeedPost) => {
    send("main", p.prompt || `${t("About this post from my feed:")}\n\n**${p.title}**\n\n${p.body.slice(0, 1200)}`).catch((e: Error) => toast(e.message || t("Could not send")));
    openThread("main");
  };
  const postDays = useMemo(() => groupPostsByDay(posts?.posts ?? []), [posts]);
  const noPosts = posts !== null && posts.posts.length === 0;

  return (
    <div className="flex h-full flex-col">
      <TabHeader
        title=""
        trailing={
          <MuseRoundButton onClick={() => setSettingsOpen(true)} label={t("Feed settings")}>
            <SlidersHorizontal size={22} />
          </MuseRoundButton>
        }
      />

      <div className="flex-1 overflow-y-auto px-3.5 pb-7">
        {/* Muse's feed: a day heading, white cards; the first card explains what drives it */}
        {postDays.length === 0 && (
          <>
            <DayTitle text={dayLabel(todayKey(), new Date())} />
            {!introAck && <IntroCard instructions={posts?.instructions ?? ""} onEdit={() => setSettingsOpen(true)} onAck={ackIntro} />}
            {noPosts && (
              <>
                <StaticCard
                  emoji="🖼️"
                  title={t("Nothing in the feed yet")}
                  body={t("As we get to know each other, new posts will show up here. Every day at {time} I read what I remember about you (your memory files, the last week of diary, your goals) and write a few short posts.", {
                    time: posts?.time ?? "08:00",
                  })}
                />
                <StaticCard emoji="📝" title={t("Steer it with one sentence")} body={t("Tap the sliders at the top right to tell me what you want more of, switch the daily routine off, or have me write the first day now.")} />
                {state.settings && !state.settings.llm_ready && (
                  <FeedCard>
                    <button type="button" onClick={() => setTab("you")} className="flex w-full items-center gap-2 p-4 text-left text-[14px] text-muted">
                      <Info size={16} className="shrink-0 text-accent" /> {t("Add a model first; the feed is written by your agent.")}
                    </button>
                  </FeedCard>
                )}
                <FeedCard>
                  <div className="p-4">
                    {writing ? (
                      <div className="flex items-center justify-center gap-2.5 text-[15px]">
                        <Loader2 size={18} className="animate-spin text-accent" /> {t("Writing…")}
                      </div>
                    ) : (
                      <button type="button" onClick={() => void writeNow()} className="h-[46px] w-full rounded-full bg-accent text-[15px] font-semibold text-accent-fg">
                        {t("Write it now")}
                      </button>
                    )}
                  </div>
                </FeedCard>
              </>
            )}
          </>
        )}
        {postDays.map(([day, list], i) => (
          <section key={day}>
            <DayTitle text={dayLabel(day, new Date(list[0].ts))} />
            {i === 0 && !introAck && <IntroCard instructions={posts?.instructions ?? ""} onEdit={() => setSettingsOpen(true)} onAck={ackIntro} />}
            {i === 0 && writing && (
              <FeedCard>
                <div className="flex items-center gap-2.5 p-4 text-[15px]">
                  <Loader2 size={18} className="animate-spin text-accent" /> {t("Writing…")}
                </div>
              </FeedCard>
            )}
            {list.map((p) => (
              <PostCard key={p.id} post={p} onDiscuss={() => discuss(p)} onInfo={() => setInfoPost(p)} />
            ))}
          </section>
        ))}

        <div className="space-y-4 px-0.5 pt-3">
        {upcoming && <NextUp data={upcoming} name={name} onSettings={() => setTab("you")} onGoals={() => setTab("goals")} />}
        {calendar?.configured && <TodayBlock data={calendar} onAsk={() => openThread("main")} />}

        {items && items.length === 0 && (!posts || posts.posts.length === 0) && (
          <div className="py-10 text-center text-muted text-[14px] px-6">
            <Moon size={28} className="mx-auto mb-2 opacity-60" />
            {t("Nothing yet. Once {name} works on a goal in the background or needs your approval, it shows up here.", { name })}
          </div>
        )}

        {items && items.length > 0 && (
          <div className="px-1 pt-1 text-[12px] font-semibold uppercase tracking-wide text-muted">{t("While you were away")}</div>
        )}
        {groups.map(([day, list]) => (
          <section key={day}>
            <div className="px-1 mb-1.5 text-[12px] uppercase tracking-wide text-muted font-semibold">{day}</div>
            <ul className="space-y-2">
              {list.map((it) => (
                <li key={it.id}>
                  <FeedRow
                    item={it}
                    unseen={it.ts > state.feedSeenAt}
                    onOpen={() => {
                      if (it.kind === "artifact" && it.path) openFile(it.path);
                      else openThread(it.thread);
                    }}
                  />
                </li>
              ))}
            </ul>
          </section>
        ))}
        </div>
      </div>

      <FeedSettingsSheet
        open={settingsOpen}
        onClose={() => setSettingsOpen(false)}
        data={posts}
        writing={writing}
        onChange={setPosts}
        onWriteNow={() => void writeNow()}
      />
      <Sheet open={infoPost !== null} onClose={() => setInfoPost(null)} title={infoPost?.title}>
        {infoPost && (
          <div className="space-y-3 px-1 pb-2">
            <InfoRow label={t("Post type")} value={areaLabel(infoPost.area)} />
            <InfoRow label={t("Written")} value={new Date(infoPost.ts).toLocaleString(intlLocale(), { dateStyle: "medium", timeStyle: "short" })} />
            <button
              type="button"
              onClick={async () => {
                const id = infoPost.id;
                setInfoPost(null);
                try {
                  await api.deleteFeedPost(id);
                  setPosts((d) => (d ? { ...d, posts: d.posts.filter((x) => x.id !== id) } : d));
                } catch (e) {
                  toast((e as Error).message);
                }
              }}
              className="w-full rounded-full bg-surface-2 py-2.5 text-[15px] font-medium text-rose-500"
            >
              {t("Delete this post")}
            </button>
          </div>
        )}
      </Sheet>
    </div>
  );
}

const INTRO_ACK_KEY = "nanomuse.feed.introAck";

function todayKey(): string {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}

/** Posts grouped by local day, newest day first (the posts come newest first already). */
function groupPostsByDay(posts: FeedPost[]): Array<[string, FeedPost[]]> {
  const map = new Map<string, FeedPost[]>();
  for (const p of posts) {
    const d = new Date(p.ts);
    const key = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
    const list = map.get(key);
    if (list) list.push(p);
    else map.set(key, [p]);
  }
  return [...map.entries()];
}

/** "Thursday morning" for today, "Yesterday" for the day before, "Monday · Sep 22" further back. */
function dayLabel(day: string, at: Date): string {
  const today = todayKey();
  const yesterday = new Date();
  yesterday.setDate(yesterday.getDate() - 1);
  const yKey = `${yesterday.getFullYear()}-${String(yesterday.getMonth() + 1).padStart(2, "0")}-${String(yesterday.getDate()).padStart(2, "0")}`;
  const weekday = at.toLocaleDateString(intlLocale(), { weekday: "long" });
  if (day === today) {
    const hour = new Date().getHours();
    const part = hour < 12 ? t("morning") : hour < 18 ? t("afternoon") : t("evening");
    return t("{weekday} {part}", { weekday, part });
  }
  if (day === yKey) return t("Yesterday");
  return `${weekday} · ${at.toLocaleDateString(intlLocale(), { month: "short", day: "numeric" })}`;
}

function DayTitle({ text }: { text: string }) {
  return <h2 className="px-1.5 pt-3.5 pb-2 text-[22px] font-bold leading-7">{text}</h2>;
}

/** White card frame shared by every card on the page. */
function FeedCard({ children, className }: { children: ReactNode; className?: string }) {
  return <div className={cx("my-1.5 overflow-hidden rounded-[18px] bg-surface dark:border dark:border-border", className)}>{children}</div>;
}

const AREA_EMOJI: Record<string, string> = {
  planning: "🗓️",
  research: "🔍",
  goals: "🎯",
  money: "💰",
  health: "🌿",
  home: "🏠",
  learning: "📚",
  people: "👥",
  files: "📄",
  fun: "🎈",
};

function areaLabel(area: string): string {
  switch (area) {
    case "planning":
      return t("Planning");
    case "research":
      return t("Research");
    case "goals":
      return t("Goals");
    case "money":
      return t("Money");
    case "health":
      return t("Health");
    case "home":
      return t("Home");
    case "learning":
      return t("Learning");
    case "people":
      return t("People");
    case "files":
      return t("Files");
    case "fun":
      return t("Fun");
    default:
      return t("Note");
  }
}

/** Muse's post: an emoji tile, the title, the body, a footer of discuss · info. */
function PostCard({ post, onDiscuss, onInfo }: { post: FeedPost; onDiscuss: () => void; onInfo: () => void }) {
  const t = useT();
  return (
    <FeedCard>
      <article className="pl-3.5 pr-2.5 pt-3.5 pb-1.5">
        <div className="flex items-start gap-3">
          <div className="flex h-11 w-11 shrink-0 items-center justify-center rounded-xl bg-surface-2 text-[22px] leading-none">{AREA_EMOJI[post.area] ?? "📝"}</div>
          <div className="min-w-0 flex-1">
            <h3 className="text-[16px] font-semibold leading-[22px]">{post.title}</h3>
            <div className="md mt-1 text-[15px] leading-[22px] text-fg/85">
              <Markdown text={post.body} />
            </div>
          </div>
        </div>
        <div className="mt-1.5 flex items-center">
          <button type="button" onClick={onDiscuss} className="flex items-center gap-1.5 rounded-full px-2 py-2 text-[14px] hover:bg-surface-2">
            <MessageCircle size={22} strokeWidth={1.8} /> {t("Discuss")}
          </button>
          <button type="button" onClick={onInfo} aria-label={t("About this post")} className="ml-auto rounded-full p-2 hover:bg-surface-2">
            <Info size={22} strokeWidth={1.8} />
          </button>
        </div>
      </article>
    </FeedCard>
  );
}

/** Muse's "About the feed" card: what drives it, the sentence itself, Edit / Got it. */
function IntroCard({ instructions, onEdit, onAck }: { instructions: string; onEdit: () => void; onAck: () => void }) {
  const t = useT();
  return (
    <FeedCard>
      <div className="px-4 py-3.5">
        <div className="text-[17px] font-semibold">{t("About the feed")}</div>
        <p className="mt-1 text-[13px] leading-[18px] text-muted">{t("Short posts your agent writes for you from what it remembers: your memory files, the last week of diary, your goals. The sentence below steers every post from now on; edit it any time.")}</p>
      </div>
      <div className="border-t border-border/70 px-4 py-3.5">
        <p className="text-[15px] leading-[22px]">{instructions || t("Build me a feed about what I care about. Keep it short and direct, easy to skim, no clickbait.")}</p>
        <div className="mt-3.5 flex justify-end gap-2.5">
          <button type="button" onClick={onEdit} className="rounded-full bg-surface-2 px-6 py-2.5 text-[15px] font-medium">
            {t("Edit")}
          </button>
          <button type="button" onClick={onAck} className="rounded-full bg-accent px-6 py-2.5 text-[15px] font-semibold text-accent-fg">
            {t("Got it")}
          </button>
        </div>
      </div>
    </FeedCard>
  );
}

function StaticCard({ emoji, title, body }: { emoji: string; title: string; body: string }) {
  return (
    <FeedCard>
      <div className="flex items-start gap-3 p-3.5">
        <div className="flex h-11 w-11 shrink-0 items-center justify-center rounded-xl bg-surface-2 text-[22px] leading-none">{emoji}</div>
        <div className="min-w-0 flex-1">
          <div className="text-[16px] font-semibold leading-[22px]">{title}</div>
          <p className="mt-1 text-[15px] leading-[22px] text-fg/85">{body}</p>
        </div>
      </div>
    </FeedCard>
  );
}

function InfoRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex items-start gap-3 text-[14.5px]">
      <span className="w-14 shrink-0 text-muted">{label}</span>
      <span className="min-w-0 flex-1 break-words">{value}</span>
    </div>
  );
}

/** The sliders sheet: the steering sentence, the daily routine (on/off, its time) and "Write it now". */
function FeedSettingsSheet({
  open,
  onClose,
  data,
  writing,
  onChange,
  onWriteNow,
}: {
  open: boolean;
  onClose: () => void;
  data: FeedPostsData | null;
  writing: boolean;
  onChange: (d: FeedPostsData) => void;
  onWriteNow: () => void;
}) {
  const { toast, state } = useStore();
  const t = useT();
  const [text, setText] = useState("");
  const [saving, setSaving] = useState(false);
  useEffect(() => {
    if (open) setText(data?.instructions ?? "");
  }, [open, data]);
  const save = async () => {
    setSaving(true);
    try {
      onChange(await api.setFeedInstructions({ instructions: text }));
      onClose();
    } catch (e) {
      toast((e as Error).message);
    } finally {
      setSaving(false);
    }
  };
  // the daily routine is saved as it is switched or moved — a setting, not part of the sentence
  const routine = async (body: { daily?: boolean; time?: string }) => {
    try {
      onChange(await api.setFeedInstructions(body));
    } catch (e) {
      toast((e as Error).message);
    }
  };
  const llmReady = state.settings?.llm_ready !== false;
  return (
    <Sheet open={open} onClose={onClose} title={t("Feed")}>
      <div className="space-y-3 px-1 pb-2">
        <p className="text-[13px] leading-[18px] text-muted">{t("Your feed is driven by the instruction below. Any edit you make here applies to every post from now on.")}</p>
        <textarea
          value={text}
          onChange={(e) => setText(e.target.value)}
          rows={4}
          maxLength={2000}
          placeholder={t("Build me a feed about what I care about. Keep it short and direct, easy to skim, no clickbait.")}
          className="w-full resize-none rounded-2xl bg-surface-2 px-3.5 py-2.5 text-[15px] leading-snug outline-none focus:ring-2 focus:ring-accent/40"
        />
        <div className="space-y-2 rounded-2xl bg-surface-2 px-3.5 py-2.5">
          <Toggle
            label={t("Write it every day")}
            hint={data?.daily === false ? undefined : t("Daily at {time}", { time: data?.time ?? "08:00" })}
            checked={data?.daily !== false}
            onChange={(v) => void routine({ daily: v })}
            disabled={!data}
          />
          {data?.daily !== false && (
            <label className="flex items-center justify-between gap-3 text-[13px] text-muted">
              <span>{t("Time of day")}</span>
              <input
                type="time"
                value={data?.time ?? "08:00"}
                onChange={(e) => e.target.value && void routine({ time: e.target.value })}
                className="rounded-lg bg-surface px-2 py-1 text-[13px] text-fg outline-none focus:ring-2 focus:ring-accent/40"
              />
            </label>
          )}
          {!llmReady && <div className="text-[12.5px] text-muted">{t("Add a model first; the feed is written by your agent.")}</div>}
        </div>
        <button
          type="button"
          onClick={onWriteNow}
          disabled={writing || !data || !llmReady}
          className="flex w-full items-center justify-center gap-2 rounded-full bg-surface-2 py-2.5 text-[15px] font-medium disabled:opacity-60"
        >
          {writing ? <Loader2 size={16} className="animate-spin" /> : <Sparkles size={16} className="text-accent" />}
          {writing ? t("Writing…") : t("Write it now")}
        </button>
        <button type="button" onClick={() => void save()} disabled={saving} className="h-[46px] w-full rounded-full bg-accent text-[15px] font-semibold text-accent-fg disabled:opacity-60">
          {t("Save")}
        </button>
      </div>
    </Sheet>
  );
}

function NextUp({
  data,
  name,
  onSettings,
  onGoals,
}: {
  data: UpcomingData;
  name: string;
  onSettings: () => void;
  onGoals: () => void;
}) {
  const t = useT();
  const next = data.queue[0];
  const active = data.reminders.filter((r): r is typeof r & { next_at: string } => r.status === "active" && !!r.next_at);
  const nextReminder = active[0];
  const activeReminders = active.length;
  return (
    <div className="rounded-3xl border border-border/70 bg-surface shadow-sm px-4 py-3.5">
      <div className="flex items-center gap-2 text-[12px] uppercase tracking-wide text-muted font-semibold">
        <Bell size={13} /> {t("Next up")}
      </div>
      {!data.proactive ? (
        <div className="mt-1.5 text-[14px] leading-snug">
          {t("Background work is off. {name} only acts when you ask.", { name })}{" "}
          <button type="button" onClick={onSettings} className="text-accent font-medium">
            {t("Turn it on")}
          </button>
        </div>
      ) : !next ? (
        <div className="mt-1.5 text-[14px] leading-snug">
          {t("No goal has a next step to work on.")}{" "}
          <button type="button" onClick={onGoals} className="text-accent font-medium">
            {t("Add one")}
          </button>
        </div>
      ) : (
        <div className="mt-1.5 text-[14px] leading-snug">
          {data.busy
            ? t("Working now")
            : data.quiet_until
              ? t("Quiet hours after {time}", { time: timeShort(data.quiet_until) })
              : data.next_pass_at
                ? t("Around {time}", { time: timeShort(data.next_pass_at) })
                : t("Soon")}
          : <span className="font-medium">{next.title}</span>
          {next.next_step && <span className="text-muted"> · {next.next_step}</span>}
          {next.overdue && <span className="text-rose-500 font-medium"> · {t("overdue")}</span>}
          {data.queue.length > 1 && <span className="text-muted"> · {t("{n} more in line", { n: data.queue.length - 1 })}</span>}
        </div>
      )}
      {data.check_ins[0] && (
        <div className="mt-1.5 text-[12.5px] text-muted">
          {t("Check-in on")} <span className="font-medium text-fg">{data.check_ins[0].title}</span> {relativeTime(data.check_ins[0].at)}
          {data.check_ins.length > 1 && ` · ${t("{n} more", { n: data.check_ins.length - 1 })}`}
        </div>
      )}
      {nextReminder && (
        <div className="mt-1.5 text-[12.5px] text-muted">
          {nextReminder.kind === "task" ? t("Routine") : t("Reminder")} <span className="font-medium text-fg">{nextReminder.text}</span>{" "}
          {relativeTime(nextReminder.next_at)}
          {activeReminders > 1 && ` · ${t("{n} more", { n: activeReminders - 1 })}`}
        </div>
      )}
    </div>
  );
}

/** Today's events (and tomorrow's, when today is done) from the connected calendars. */
function TodayBlock({ data, onAsk }: { data: CalendarData; onAsk: () => void }) {
  const t = useT();
  // the server sends what overlaps today and tomorrow; what began by today is today's
  const isToday = (e: CalendarEvent) => e.start.slice(0, 10) <= data.today;
  const todays = data.events.filter(isToday);
  const later = data.events.filter((e) => !isToday(e));
  const now = new Date();
  const over = (e: CalendarEvent) => !e.all_day && new Date(e.end) < now;
  const broken = data.feeds.filter((f) => f.error);
  const showTomorrow = todays.every(over) && later.length > 0;
  const list = showTomorrow ? later : todays;
  return (
    <div className="rounded-3xl border border-border/70 bg-surface shadow-sm px-4 py-3.5">
      <div className="flex items-center gap-2 text-[12px] uppercase tracking-wide text-muted font-semibold">
        <CalendarDays size={13} /> {showTomorrow ? t("Tomorrow") : t("Today")}
        {list.length > 0 && <span className="ml-auto normal-case tracking-normal font-normal">{t("{n} scheduled", { n: list.length })}</span>}
      </div>
      {list.length === 0 ? (
        <div className="mt-1.5 text-[14px] leading-snug text-muted">{t("Nothing on the calendar today.")}</div>
      ) : (
        <ul className="mt-1.5 space-y-1">
          {list.map((e) => (
            <li key={`${e.uid}-${e.start}`} className={cx("flex items-baseline gap-2.5 text-[14px] leading-snug", !showTomorrow && over(e) && "opacity-50")}>
              <span className="w-[86px] shrink-0 tabular-nums text-[12.5px] text-muted">
                {e.all_day ? t("all day") : `${e.start.slice(11, 16)}–${e.end.slice(11, 16)}`}
              </span>
              <span className="min-w-0 flex-1">
                <span className="font-medium">{e.summary}</span>
                {e.location && (
                  <span className="text-muted text-[12.5px]">
                    {" "}
                    <MapPin size={11} className="inline -mt-0.5" /> {e.location}
                  </span>
                )}
              </span>
            </li>
          ))}
        </ul>
      )}
      {broken.length > 0 && <div className="mt-1.5 text-[12px] text-rose-500">{t("{name} could not be read", { name: broken.map((f) => f.name).join(", ") })}</div>}
      <button type="button" onClick={onAsk} className="mt-2 text-[12.5px] text-accent font-medium">
        {t("Ask about your week")}
      </button>
    </div>
  );
}

/** Markdown down to its words for a three-line preview. */
function plain(md: string): string {
  return md
    .replace(/```[\s\S]*?```/g, " ")
    .replace(/^#{1,6}\s+/gm, "")
    .replace(/(\*\*|__|`)/g, "")
    .replace(/^\s*[-*+]\s+/gm, "• ")
    .replace(/\[([^\]]+)\]\([^)]*\)/g, "$1")
    .trim();
}

function FeedRow({ item, unseen, onOpen }: { item: FeedItem; unseen: boolean; onOpen: () => void }) {
  const t = useT();
  const icon =
    item.kind === "approval" ? (
      <ShieldAlert size={18} />
    ) : item.kind === "question" ? (
      <MessageCircleQuestion size={18} />
    ) : item.kind === "artifact" ? (
      <FileText size={18} />
    ) : item.quiet ? (
      <Moon size={18} />
    ) : item.title.startsWith("New mail: ") ? (
      <Mail size={18} />
    ) : item.title.startsWith("Coming up: ") ? (
      <CalendarClock size={18} />
    ) : item.title.startsWith("Webhook: ") ? (
      <Webhook size={18} />
    ) : (
      <Sparkles size={18} />
    );
  const tone =
    item.kind === "approval"
      ? "bg-amber-500/12 text-amber-600 dark:text-amber-300"
      : item.kind === "question"
        ? "bg-accent/12 text-accent"
        : "bg-surface-2 text-muted";
  if (item.quiet) {
    // a pass that found nothing worth interrupting you for: one line, no card
    return (
      <button type="button" onClick={onOpen} className="w-full text-left px-2 py-1.5 flex items-center gap-2 text-[12.5px] text-muted">
        <Moon size={13} className="shrink-0" />
        <span className="truncate">
          {t("{label}: nothing new", { label: localLabel(item.title.replace(/^Working on your goal: /, "")) })}{item.text ? `: ${item.text}` : ""}
        </span>
        <span className="ml-auto shrink-0">{relativeTime(item.ts)}</span>
      </button>
    );
  }
  return (
    <button
      type="button"
      onClick={onOpen}
      className={cx(
        "w-full text-left rounded-3xl border bg-surface shadow-sm px-4 py-3 active:scale-[0.99] transition",
        unseen ? "border-accent/40" : "border-border/70",
      )}
    >
      <div className="flex items-start gap-3">
        <div className={cx("rounded-2xl p-2 mt-0.5", tone)}>{icon}</div>
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2">
            <div className="font-semibold text-[14.5px] leading-snug truncate">{localLabel(item.title)}</div>
            {unseen && <span className="h-2 w-2 rounded-full bg-accent shrink-0" />}
          </div>
          {item.text && <div className="mt-0.5 text-[13.5px] text-muted leading-snug line-clamp-3 whitespace-pre-wrap">{plain(item.text)}</div>}
          <div className="mt-1.5 flex items-center gap-2 text-[12px] text-muted">
            <span>{relativeTime(item.ts)}</span>
            <span>·</span>
            <span className="truncate">{item.thread === "main" ? t(item.thread_title) : item.thread_title}</span>
            <span className="ml-auto flex items-center gap-1 text-accent font-medium">
              {item.kind === "artifact" ? t("Open") : item.kind === "approval" || item.kind === "question" ? t("Answer") : t("Open chat")}
              <ArrowRight size={13} />
            </span>
          </div>
        </div>
      </div>
    </button>
  );
}

function groupByDay(items: FeedItem[]): Array<[string, FeedItem[]]> {
  const today = new Date();
  const yesterday = new Date(today);
  yesterday.setDate(today.getDate() - 1);
  const label = (iso: string) => {
    const d = new Date(iso);
    if (d.toDateString() === today.toDateString()) return t("Today");
    if (d.toDateString() === yesterday.toDateString()) return t("Yesterday");
    return d.toLocaleDateString(intlLocale(), { weekday: "long", month: "short", day: "numeric" });
  };
  const map = new Map<string, FeedItem[]>();
  for (const it of items) {
    const k = label(it.ts);
    map.set(k, [...(map.get(k) ?? []), it]);
  }
  return [...map.entries()];
}
