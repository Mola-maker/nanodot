import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import { DotStatus } from "./DotStatus";

describe("Dot state announcements", () => {
  it("announces errors and provides an enabled retry action", () => {
    const html = renderToStaticMarkup(createElement(DotStatus, { state: "error", message: "429: Try again later", onRetry: () => undefined }));
    expect(html).toContain('role="alert"');
    expect(html).toContain("429: Try again later");
    expect(html).toContain("Try again");
  });
  it("announces loading without a looping spinner", () => {
    const html = renderToStaticMarkup(createElement(DotStatus, { state: "loading", message: "Connecting" }));
    expect(html).toContain('aria-busy="true"');
    expect(html).toContain("Connecting");
    expect(html).not.toContain("animate-spin");
  });
});
