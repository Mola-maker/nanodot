/**
 * System appearance is the default; a chosen light or dark setting is retained.
 * index.html applies
 * the stored choice before the first paint; this module keeps <html data-theme> and the
 * theme-color meta in step afterwards.
 */
import { useSyncExternalStore } from "react";

export type ThemeSetting = "light" | "dark" | "system";
export type Theme = "light" | "dark";

const STORAGE_KEY = "nanomuse_theme";
const COLORS: Record<Theme, string> = { light: "#F4F8FF", dark: "#101D30" };

function load(): ThemeSetting {
  try {
    const v = localStorage.getItem(STORAGE_KEY);
    if (v === "light" || v === "dark" || v === "system") return v;
  } catch {
    // no storage: follow the system
  }
  return "system";
}

let setting: ThemeSetting = load();
const listeners = new Set<() => void>();
const media = typeof matchMedia === "function" ? matchMedia("(prefers-color-scheme: dark)") : null;

function systemTheme(): Theme {
  return media?.matches ? "dark" : "light";
}

export function getThemeSetting(): ThemeSetting {
  return setting;
}

export function getTheme(): Theme {
  return setting === "system" ? systemTheme() : setting;
}

export function setThemeSetting(next: ThemeSetting): void {
  setting = next;
  try {
    localStorage.setItem(STORAGE_KEY, next);
  } catch {
    // fine — the choice lasts for this page then
  }
  applyTheme();
  listeners.forEach((fn) => fn());
}

/** <html data-theme> and the browser chrome colour follow the resolved theme. */
export function applyTheme(): void {
  if (typeof document === "undefined") return;
  const theme = getTheme();
  document.documentElement.dataset.theme = theme;
  document.documentElement.dataset.dotTheme = theme;
  const meta = document.querySelector('meta[name="theme-color"]');
  if (meta) meta.setAttribute("content", COLORS[theme]);
}

media?.addEventListener?.("change", () => {
  if (setting === "system") {
    applyTheme();
    listeners.forEach((fn) => fn());
  }
});

function subscribe(fn: () => void): () => void {
  listeners.add(fn);
  return () => listeners.delete(fn);
}

export function useThemeSetting(): ThemeSetting {
  return useSyncExternalStore(subscribe, getThemeSetting, getThemeSetting);
}

export function useTheme(): Theme {
  return useSyncExternalStore(subscribe, getTheme, getTheme);
}
