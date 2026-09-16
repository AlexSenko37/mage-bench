import { describe, expect, it } from "vitest";

import { applyViewParam, DEFAULT_VIEW, parseViewParam } from "../src/utils/replay-view.ts";

const AVAILABLE = ["decks", "draft", "commentary"];

describe("parseViewParam", () => {
  it("defaults to the replay when there is no view parameter", () => {
    expect(parseViewParam("", AVAILABLE)).toBe(DEFAULT_VIEW);
    expect(parseViewParam("?s=137", AVAILABLE)).toBe(DEFAULT_VIEW);
  });

  it("returns a view the game has", () => {
    expect(parseViewParam("?view=commentary", AVAILABLE)).toBe("commentary");
    expect(parseViewParam("?s=137&view=draft", AVAILABLE)).toBe("draft");
  });

  it("falls back for a tab this game does not have", () => {
    // A commentary link for a game nobody has written one for still opens the replay.
    expect(parseViewParam("?view=commentary", ["decks"])).toBe(DEFAULT_VIEW);
  });

  it("falls back for an unknown value", () => {
    expect(parseViewParam("?view=nonsense", AVAILABLE)).toBe(DEFAULT_VIEW);
  });

  it("accepts the default view explicitly", () => {
    expect(parseViewParam("?view=replay", AVAILABLE)).toBe(DEFAULT_VIEW);
  });
});

describe("applyViewParam", () => {
  it("adds the view to an empty search", () => {
    expect(applyViewParam("", "commentary")).toBe("?view=commentary");
  });

  it("keeps the snapshot and audit parameters", () => {
    expect(applyViewParam("?s=137", "commentary")).toBe("?s=137&view=commentary");
    expect(applyViewParam("?s=12&d=3", "decks")).toBe("?s=12&d=3&view=decks");
  });

  it("replaces a view that is already there", () => {
    expect(applyViewParam("?view=decks&s=4", "draft")).toBe("?view=draft&s=4");
  });

  it("drops the parameter for the replay", () => {
    expect(applyViewParam("?view=commentary", DEFAULT_VIEW)).toBe("");
    expect(applyViewParam("?s=137&view=commentary", "replay")).toBe("?s=137");
  });
});
