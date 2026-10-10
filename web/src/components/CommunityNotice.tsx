import { Bug, ExternalLink, Github, HeartHandshake } from "lucide-react";
import { useT } from "../i18n";
import { cx } from "../util";
import { markStarred } from "./StarNudge";

export const REPO_URL = "https://github.com/Mola-maker/nanodot";
export const ISSUES_URL = "https://github.com/Mola-maker/nanodot/issues/new/choose";
/** The upstream service notice, including its privacy and data policies. */
export const NOTICE_URL = "https://nanomuse.cn/#open-source";

/** Introduces the client, its model choices, provider terms and community links. */
export function CommunityNotice({ compact = false, className }: { compact?: boolean; className?: string }) {
  const t = useT();
  return (
    <section className={cx("rounded-[22px] border border-accent/25 bg-accent/[0.06] p-4 dark:bg-accent/[0.10]", className)}>
      <a href={NOTICE_URL} target="_blank" rel="noreferrer" className="flex items-start gap-2 text-[13.5px] font-semibold hover:text-accent">
        <HeartHandshake size={16} className="mt-0.5 shrink-0 text-accent" />
        <span className="min-w-0 flex-1 leading-snug">{t("An open-source personal agent")}</span>
        <ExternalLink size={12} className="mt-1 shrink-0 text-muted" />
      </a>
      <p className={cx("mt-1.5 leading-relaxed text-fg/85", compact ? "text-[12.5px]" : "text-[13px]")}>
        {t(
          "Nanodot is an open-source personal agent based on nanoMuse. Use your own model API key or connect to nanoMuse Cloud. Model usage and allowances follow the provider's terms. The provider's privacy policy explains what its relay stores; Settings → Data controls manages your account data.",
        )}
      </p>
      <p className={cx("mt-1.5 leading-relaxed text-fg/85", compact ? "text-[12.5px]" : "text-[13px]")}>
        {t("The people building it use it every day. Report a bug, ask for a feature, send a pull request: that is what moves the project. A star on GitHub helps others find it.")}
      </p>
      <div className="mt-3 flex flex-wrap gap-2 text-[12.5px] font-medium">
        {/* going to GitHub from here counts as the star being done: the asks stop (C1) */}
        <a href={REPO_URL} target="_blank" rel="noreferrer" onClick={markStarred} className="inline-flex items-center gap-1.5 rounded-full bg-fg px-3 py-1.5 text-bg hover:opacity-90">
          <Github size={13} /> {t("Star on GitHub")}
        </a>
        <a href={ISSUES_URL} target="_blank" rel="noreferrer" className="inline-flex items-center gap-1.5 rounded-full border border-border bg-surface px-3 py-1.5 text-fg hover:bg-surface-2">
          <Bug size={13} /> {t("Report a bug or ask for a feature")}
        </a>
      </div>
    </section>
  );
}
