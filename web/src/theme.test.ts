import { afterEach, describe, expect, it, vi } from "vitest";

afterEach(() => vi.unstubAllGlobals());

async function appearance(saved: string | null, dark: boolean) {
  vi.resetModules();
  const storage = { getItem: vi.fn(() => saved), setItem: vi.fn() };
  vi.stubGlobal("localStorage", storage);
  vi.stubGlobal("matchMedia", () => ({ matches: dark, addEventListener: () => undefined }));
  return { theme: await import("./theme"), storage };
}

describe("Nanodot default appearance", () => {
  it("follows system dark appearance for a new or invalid preference", async () => {
    for (const saved of [null, "unknown"]) {
      const { theme } = await appearance(saved, true);
      expect(theme.getThemeSetting()).toBe("system");
      expect(theme.getTheme()).toBe("dark");
    }
  });
  it("preserves explicitly chosen light and dark appearances", async () => {
    expect((await appearance("light", true)).theme.getTheme()).toBe("light");
    expect((await appearance("dark", false)).theme.getTheme()).toBe("dark");
  });
  it("keeps the existing preference storage key", async () => {
    const { theme, storage } = await appearance(null, false);
    theme.setThemeSetting("dark");
    expect(storage.setItem).toHaveBeenCalledWith("nanomuse_theme", "dark");
  });
});
