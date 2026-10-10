import { describe, expect, it, vi } from "vitest";
import { DOT, dragonClipUrl, dragonUrl, isDragon, studioClipUrl, studioUrl } from "./avatars";

describe("Dot avatar compatibility", () => {
  it("recognizes Dot and legacy built-in IDs without claiming a studio or emoji ID", () => {
    for (const id of [undefined, null, DOT, "dragon", "panda", "sunny"]) expect(isDragon(id)).toBe(true);
    for (const id of ["", "custom-123", "future-avatar"]) expect(isDragon(id)).toBe(false);
  });
  it("uses offline stills, including an idle fallback for an unknown mood", () => {
    expect(dragonUrl("working")).toBe("/avatars/dragon-working.webp");
    expect(dragonUrl("future")).toBe("/avatars/dragon-idle.webp");
    for (const mood of ["idle", "working", "waiting", "happy", "error"]) expect(dragonClipUrl(mood)).toBeNull();
  });
  it("keeps custom still and clip paths", () => {
    vi.stubGlobal("window", { location: { href: "https://example.invalid/", origin: "https://example.invalid", protocol: "https:", host: "example.invalid" } });
    vi.stubGlobal("localStorage", { getItem: () => null });
    expect(studioUrl("custom-123", "working")).toContain("custom-123");
    expect(studioClipUrl("custom-123", "happy")).toContain("happy.mp4");
    expect(studioClipUrl("custom-123", "error")).toBeNull();
    vi.unstubAllGlobals();
  });
});
