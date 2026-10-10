import { ExternalLink, Loader2, LogIn, LogOut, MessageSquareText } from "lucide-react";
import { useCallback, useEffect, useRef, useState } from "react";
import { api } from "../api";
import { useT } from "../i18n";
import { useStore } from "../store";
import { invalidateProviders } from "../providers";
import type { ChatGptStatus } from "../types";
import { cx } from "../util";
import { primaryBtn, secondaryBtn } from "./Form";

/**
 * Sign in with a ChatGPT plan (contract C11): the runtime runs the Codex PKCE flow
 * (`nanomuse chatgpt login`), this card opens the page it names and polls until the
 * tokens are in the runtime's store. What the sign-in gives is chat and the hands only —
 * the Codex backend has no image or video endpoints — and the card says so, with the
 * honest line about OpenAI's terms. A runtime without the HTTP routes answers 404; the
 * card then names the command to run in a terminal instead of pretending.
 */
export const CHATGPT_CAVEAT = "OpenAI's terms cover using a ChatGPT plan inside OpenAI's own Codex; other apps have had this access cut off before (OpenCode, January 2026). If it stops working, an API key does.";

const POLL_MS = 2000;
const GIVE_UP_MS = 10 * 60 * 1000;

export function ChatGptSignIn({ compact, onChanged }: { compact?: boolean; onChanged?: () => void }) {
  const t = useT();
  const { toast } = useStore();
  const [status, setStatus] = useState<ChatGptStatus | null>(null);
  const [routes, setRoutes] = useState<"unknown" | "yes" | "no">("unknown");
  const [busy, setBusy] = useState<"login" | "use" | "logout" | "paste" | null>(null);
  const [waiting, setWaiting] = useState<string | null>(null);
  // the runtime could not listen on port 1455 (taken, or the browser is on another machine): the
  // address the browser lands on has to be pasted here
  const [portBound, setPortBound] = useState(true);
  const [pasted, setPasted] = useState("");
  const [error, setError] = useState<string | null>(null);
  const started = useRef(0);

  const refresh = useCallback(async () => {
    try {
      const s = await api.chatgptStatus();
      setStatus(s);
      setRoutes("yes");
      return s;
    } catch (e) {
      // 404 "Not Found": the runtime of before this round
      if (/not found/i.test((e as Error).message)) setRoutes("no");
      else setError((e as Error).message);
      return null;
    }
  }, []);

  useEffect(() => {
    // a sign-in already under way (another tab, the CLI): this card follows it too
    void refresh().then((s) => {
      if (s?.pending && s.url) {
        started.current = Date.now();
        setPortBound(s.port_bound !== false);
        setWaiting(s.url);
      }
    });
  }, [refresh]);

  // while a sign-in is under way, ask every two seconds until the store has tokens
  useEffect(() => {
    if (!waiting) return;
    const id = setInterval(() => {
      void refresh().then((s) => {
        if (s?.signed_in) {
          setWaiting(null);
          invalidateProviders();
          toast(t("Signed in with {label}.", { label: s.label || "ChatGPT" }));
          onChanged?.();
        } else if (s && s.pending === false && s.error) {
          setWaiting(null);
          setError(t(s.error));
        } else if (Date.now() - started.current > GIVE_UP_MS) {
          setWaiting(null);
          setError(t("The sign-in did not finish in ten minutes. Try again."));
        }
      });
    }, POLL_MS);
    return () => clearInterval(id);
  }, [waiting, refresh, onChanged, toast, t]);

  const login = async () => {
    setBusy("login");
    setError(null);
    try {
      const r = await api.chatgptLogin();
      started.current = Date.now();
      setPortBound(r.port_bound !== false);
      setPasted("");
      setWaiting(r.url);
      window.open(r.url, "_blank", "noopener");
    } catch (e) {
      if (/not found/i.test((e as Error).message)) setRoutes("no");
      else setError((e as Error).message);
    } finally {
      setBusy(null);
    }
  };

  // the address the browser ended on, handed to the runtime by hand; the poll above sees the result
  const submitPasted = async () => {
    const url = pasted.trim();
    if (!url) return;
    setBusy("paste");
    setError(null);
    try {
      await api.chatgptCallback(url);
      setPasted("");
    } catch (e) {
      const message = (e as Error).message;
      if (/state/i.test(message)) setError(t("That address belongs to another sign-in attempt. Open the page again and paste the address it ends on."));
      else if (/no sign-in|409/i.test(message)) {
        setWaiting(null);
        setError(t("The sign-in did not finish in ten minutes. Try again."));
      } else setError(message);
    } finally {
      setBusy(null);
    }
  };

  const adoptForChat = async () => {
    setBusy("use");
    setError(null);
    try {
      // `[llm] provider = "chatgpt"`: the runtime calls the Codex endpoint itself with its token store
      await api.setLLM({ provider: "chatgpt", model: status?.models?.[0] ?? "", base_url: "", api_key: "" });
      invalidateProviders();
      toast(t("ChatGPT answers the chat now."));
      onChanged?.();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(null);
    }
  };

  const logout = async () => {
    setBusy("logout");
    setError(null);
    try {
      await api.chatgptLogout();
      invalidateProviders();
      await refresh();
      onChanged?.();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(null);
    }
  };

  const covers = t("Covers chat and the hands. Pictures and clips are not part of it; those need a provider with image or video models.");

  return (
    <div className={cx("space-y-2", !compact && "rounded-2xl bg-surface-2/60 p-3")}>
      {!compact && (
        <div className="flex items-center gap-2 text-[13px] font-medium">
          <MessageSquareText size={15} className="text-accent" /> {t("Sign in with ChatGPT")}
        </div>
      )}
      {routes === "no" ? (
        <p className="text-[12.5px] text-muted">
          {t("This Nanodot has no sign-in route yet. In a terminal on this computer, run")} <code className="rounded bg-surface-2 px-1 py-0.5 text-[12px]">nanomuse chatgpt login</code> {t("and reload this page.")}
        </p>
      ) : status?.signed_in ? (
        <div className="space-y-2">
          <p className="text-[12.5px]">
            <span className="font-medium">{t("Signed in with {label}.", { label: status.label || "ChatGPT" })}</span> <span className="text-muted">{covers}</span>
          </p>
          <div className="flex flex-wrap gap-2">
            <button type="button" disabled={busy !== null} onClick={() => void adoptForChat()} className={cx(primaryBtn, "inline-flex items-center gap-1.5 py-2")}>
              {busy === "use" ? <Loader2 size={14} className="animate-spin" /> : <MessageSquareText size={14} />} {t("Use it for the chat")}
            </button>
            <button type="button" disabled={busy !== null} onClick={() => void logout()} className={cx(secondaryBtn, "inline-flex items-center gap-1.5")}>
              {busy === "logout" ? <Loader2 size={14} className="animate-spin" /> : <LogOut size={14} />} {t("Sign out of ChatGPT")}
            </button>
          </div>
        </div>
      ) : waiting ? (
        <div className="space-y-2">
          <p className="flex items-start gap-2 text-[12.5px] text-muted">
            <Loader2 size={14} className="mt-0.5 shrink-0 animate-spin" />
            <span>
              {portBound
                ? t("A page from OpenAI opened in a new tab. Sign in there; this card follows on its own.")
                : t("A page from OpenAI opened in a new tab. Sign in there; when the browser ends on an address that will not load, paste that address below.")}
            </span>
          </p>
          <div className="flex flex-wrap gap-2">
            <a href={waiting} target="_blank" rel="noopener noreferrer" className={cx(secondaryBtn, "inline-flex items-center gap-1.5")}>
              <ExternalLink size={14} /> {t("Open the page again")}
            </a>
            <button type="button" onClick={() => setWaiting(null)} className={secondaryBtn}>
              {t("Cancel")}
            </button>
          </div>
          {/* the paste box: needed when the runtime could not take the callback itself, offered either way */}
          <form
            className="flex flex-wrap items-center gap-2"
            onSubmit={(e) => {
              e.preventDefault();
              void submitPasted();
            }}
          >
            <input
              type="url"
              value={pasted}
              onChange={(e) => setPasted(e.target.value)}
              placeholder="http://localhost:1455/auth/callback?code=…"
              aria-label={t("The address the browser ended on")}
              className="min-w-0 flex-1 rounded-lg border border-border bg-surface px-2 py-1.5 text-[12.5px]"
            />
            <button type="submit" disabled={busy !== null || !pasted.trim()} className={cx(secondaryBtn, "inline-flex items-center gap-1.5")}>
              {busy === "paste" ? <Loader2 size={14} className="animate-spin" /> : null} {t("Use this address")}
            </button>
          </form>
          {!portBound && <p className="text-[11.5px] text-muted">{t("This Nanodot runs where the browser cannot reach port 1455, so it cannot pick the sign-in up by itself.")}</p>}
        </div>
      ) : (
        <div className="space-y-2">
          <p className="text-[12.5px] text-muted">{covers}</p>
          <button type="button" disabled={busy !== null || routes === "unknown"} onClick={() => void login()} className={cx(primaryBtn, "inline-flex items-center gap-1.5 py-2")}>
            {busy === "login" ? <Loader2 size={14} className="animate-spin" /> : <LogIn size={14} />} {t("Sign in with ChatGPT")}
          </button>
        </div>
      )}
      <p className="text-[11.5px] leading-snug text-muted">{t(CHATGPT_CAVEAT)}</p>
      {error && <p className="text-[12px] text-rose-600 dark:text-rose-300">{error}</p>}
    </div>
  );
}
