import { Clock, Flag, Loader2, MessageCircle, RefreshCw } from "lucide-react";
import { useEffect, useState } from "react";
import { api } from "../api";
import { Sheet } from "../components/Sheet";
import { TabHeader } from "../components/TabHeader";
import { useLocale, useT } from "../i18n";
import { asIdea, catalogue } from "../ideas";
import { useStore } from "../store";
import type { Idea, IdeasData } from "../types";
import { relativeTime } from "../util";
import { CATEGORIES } from "../goals";

/** The areas an idea can belong to, in the order they are listed; the emoji Muse puts in front of a row, and the label. */
const AREAS: Array<{ id: string; label: string; emoji: string }> = [
  { id: "planning", label: "Planning", emoji: "🗓️" },
  { id: "goals", label: "Goals", emoji: "🎯" },
  { id: "research", label: "Research", emoji: "🔍" },
  { id: "money", label: "Money", emoji: "💰" },
  { id: "health", label: "Health", emoji: "🌿" },
  { id: "home", label: "Home", emoji: "🏠" },
  { id: "learning", label: "Learning", emoji: "📚" },
  { id: "people", label: "People", emoji: "👥" },
  { id: "files", label: "Files & tools", emoji: "📄" },
  { id: "fun", label: "Just for you", emoji: "🎈" },
];

/**
 * Muse "always thinks about what it can do for you" — suggestions from goals, memory and recent
 * chat once the model has written some; until then the curated catalogue every client ships
 * (contract C5), in the app's language.
 */
export function IdeasScreen() {
  const { state, send, openThread, toast } = useStore();
  const [data, setData] = useState<IdeasData | null>(null);
  const [loading, setLoading] = useState(false);
  const name = state.profile?.name ?? "Dot";
  const t = useT();
  const locale = useLocale();

  const load = async (refresh = false) => {
    setLoading(true);
    try {
      const d = await api.ideas(refresh);
      setData(d);
      if (d.error) toast(t("Could not refresh ideas: {error}", { error: d.error }));
    } catch (e) {
      toast((e as Error).message);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void load(false);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // the model's ideas, grouped by area; or the catalogue's sections, each idea with its own emoji
  const fromModel = data?.source === "model" && data.ideas.length > 0;
  const groups: Group[] = fromModel
    ? groupByArea(data.ideas)
    : catalogue(locale).sections.map((s) => ({ id: s.id, title: s.title, ideas: s.ideas.map(asIdea) }));
  const [selected, setSelected] = useState<{ idea: Idea; emoji: string } | null>(null);
  const sendIdea = (idea: Idea) => {
    setSelected(null);
    send("main", idea.prompt).catch((e: Error) => toast(e.message || t("Could not send")));
    openThread("main");
  };
  // A routine idea becomes a daily reminder that runs the prompt (the phone's "Create routine").
  const createRoutine = async (idea: Idea) => {
    setSelected(null);
    const time = idea.time && /^([01]\d|2[0-3]):[0-5]\d$/.test(idea.time) ? idea.time : "09:00";
    try {
      await api.createReminder({ text: idea.prompt, kind: "task", repeat: `daily ${time}` });
      toast(t("Routine set: every day at {time}. It is listed under Goals.", { time }));
    } catch (e) {
      toast((e as Error).message);
    }
  };
  // A goal idea opens the goal conversation in the chat, the way the Goals tab's categories do.
  const startGoal = (idea: Idea) => {
    setSelected(null);
    const category = CATEGORIES.find((c) => c.id === idea.category) ?? CATEGORIES[CATEGORIES.length - 1];
    const opener = t("I'd like to create a {category} goal. Ask me a few short questions, one at a time (what exactly I want, why and by when, how often to check in), then create it with concrete steps using the goals tool.", {
      category: t(category.label),
    });
    send("main", `${opener} ${idea.prompt}`).catch((e: Error) => toast(e.message || t("Could not send")));
    openThread("main");
  };
  const kindOf = (idea: Idea) => (idea.kind === "routine" || idea.kind === "goal" ? idea.kind : "chat");

  // Muse's Ideas page: a big title, then rows of "emoji · bold pitch · grey detail" grouped under
  // section headers (the first group has none). Tapping a row opens a small sheet that says what
  // the idea will do and offers to do it.
  return (
    <div className="flex h-full flex-col">
      <TabHeader title={t("Ideas")}>
        <button
          type="button"
          onClick={() => void load(true)}
          disabled={loading}
          aria-label={t("Refresh ideas")}
          className="flex h-10 w-10 items-center justify-center rounded-full bg-surface text-fg shadow-[0_1px_4px_rgba(0,0,0,0.14)] disabled:opacity-60 dark:border dark:border-border dark:shadow-none"
        >
          {loading ? <Loader2 size={20} className="animate-spin" /> : <RefreshCw size={19} />}
        </button>
      </TabHeader>
      <div className="flex-1 overflow-y-auto pb-6">
        {groups.map(({ id, title, ideas }, index) => (
          <section key={id}>
            {(index > 0 || !fromModel) && <h2 className="px-5 pt-[22px] pb-1 text-[20px] font-bold">{fromModel ? t(title) : title}</h2>}
            <ul>
              {ideas.map((idea) => (
                <li key={idea.title}>
                  <button type="button" onClick={() => setSelected({ idea, emoji: idea.emoji })} className="flex w-full items-start gap-4 px-5 py-3.5 text-left active:bg-surface-2/70">
                    <span className="flex w-10 shrink-0 justify-center text-[28px] leading-[34px]">{idea.emoji}</span>
                    <span className="min-w-0 flex-1 space-y-1">
                      <span className="block text-[16px] font-semibold leading-[22px]">{idea.title}</span>
                      {idea.detail && <span className="line-clamp-4 block text-[13.5px] leading-[19px] text-muted">{idea.detail}</span>}
                    </span>
                  </button>
                  <div className="ml-[76px] mr-5 border-b border-border/70" />
                </li>
              ))}
            </ul>
          </section>
        ))}
        {data && (
          <div className="px-5 pt-4 text-[12.5px] text-muted">
            {data.source === "model" ? t("Generated {when}", { when: relativeTime(data.generated_at) }) : t("Starter ideas. Refresh once {name} knows you better.", { name })}
          </div>
        )}
      </div>

      <Sheet open={selected !== null} onClose={() => setSelected(null)}>
        {selected && (
          <div className="px-1 pb-2">
            <div className="flex items-center gap-3">
              <span className="text-[30px] leading-none">{selected.emoji}</span>
              <h3 className="flex-1 text-[18px] font-bold leading-6">{selected.idea.title}</h3>
            </div>
            {selected.idea.detail && <p className="mt-3 text-[15px] leading-[22px]">{selected.idea.detail}</p>}
            <div className="mt-3.5 flex items-center gap-1.5 text-[13px] text-muted">
              {kindOf(selected.idea) === "routine" && (
                <>
                  <Clock size={16} /> {t("Creates a routine")}
                  {selected.idea.time ? ` · ${selected.idea.time}` : ""}
                </>
              )}
              {kindOf(selected.idea) === "goal" && (
                <>
                  <Flag size={16} /> {t("Creates a goal")}
                </>
              )}
              {kindOf(selected.idea) === "chat" && (
                <>
                  <MessageCircle size={16} /> {t("Starts a conversation")}
                </>
              )}
            </div>
            {kindOf(selected.idea) === "routine" && (
              <button type="button" onClick={() => void createRoutine(selected.idea)} className="mt-[22px] flex h-[50px] w-full items-center justify-center gap-2 rounded-full bg-accent text-[16px] font-semibold text-accent-fg">
                <Clock size={18} /> {t("Create routine")}
              </button>
            )}
            {kindOf(selected.idea) === "goal" && (
              <button type="button" onClick={() => startGoal(selected.idea)} className="mt-[22px] flex h-[50px] w-full items-center justify-center gap-2 rounded-full bg-accent text-[16px] font-semibold text-accent-fg">
                <Flag size={18} /> {t("Start goal")}
              </button>
            )}
            {kindOf(selected.idea) === "chat" && (
              <button type="button" onClick={() => sendIdea(selected.idea)} className="mt-[22px] flex h-[50px] w-full items-center justify-center gap-2 rounded-full bg-accent text-[16px] font-semibold text-accent-fg">
                <MessageCircle size={18} /> {t("Send to chat")}
              </button>
            )}
          </div>
        )}
      </Sheet>
    </div>
  );
}

/** A section of the page: the model's area, or the catalogue's section; every idea with its emoji. */
interface Group {
  id: string;
  title: string;
  ideas: Array<Idea & { emoji: string }>;
}

function groupByArea(ideas: Idea[]): Group[] {
  const fallback = AREAS[AREAS.length - 1];
  const out = AREAS.map((area) => ({ id: area.id, title: area.label, ideas: [] as Array<Idea & { emoji: string }> }));
  for (const idea of ideas) {
    const area = AREAS.find((a) => a.id === (idea.area || fallback.id)) ?? fallback;
    const hit = out.find((g) => g.id === area.id) ?? out[out.length - 1];
    hit.ideas.push({ ...idea, emoji: area.emoji });
  }
  return out.filter((g) => g.ideas.length > 0);
}
