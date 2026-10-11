import { useT } from "./i18n";
import type { ReactNode } from "react";

/** State from the owning query or stream, with text that remains accessible without motion. */
export function DotStatus({ state, message, onRetry }: { state: "loading" | "empty" | "error" | "ready"; message: string; onRetry?: () => void }): ReactNode {
  const t = useT();
  return <div className={`dot-status dot-status-${state}`} role={state === "error" ? "alert" : "status"} aria-live={state === "error" ? "assertive" : "polite"} aria-busy={state === "loading"}>
    <span aria-hidden="true">{state === "error" ? "!" : state === "ready" ? "✓" : "●"}</span>
    <span>{message}</span>
    {onRetry && <button type="button" onClick={onRetry}>{t("Try again")}</button>}
  </div>;
}
