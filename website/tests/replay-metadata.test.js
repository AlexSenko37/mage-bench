import { describe, expect, it } from "vitest";
import { buildReplayTitle } from "../src/utils/replay-metadata";

function player(name, model, effort, extra = {}) {
  return { name, model, reasoning_effort: effort, ...extra };
}

describe("buildReplayTitle", () => {
  it("names both seats by model and effort", () => {
    const title = buildReplayTitle([
      player("PilotA", "anthropic/claude-fable-5.1", "low"),
      player("PilotB", "moonshotai/kimi-k3", "max"),
    ]);
    expect(title).toBe("Fabl51-low vs KimiK3-max");
  });

  it("leaves the deck name out of the title", () => {
    // For a drafted game deck_name is the generated filename -- seat, archetype and draft
    // hash -- almost all of which the label already says. It crowded the title badly.
    const title = buildReplayTitle([
      player("PilotA", "anthropic/claude-fable-5.1", "low", {
        deck_name: "fable51 low A Gruul 31b002ee",
      }),
      player("PilotB", "moonshotai/kimi-k3", "max", {
        deck_name: "kimik3 max B Gruul 31b002ee",
      }),
    ]);
    expect(title).toBe("Fabl51-low vs KimiK3-max");
    expect(title).not.toContain("(");
    expect(title).not.toContain("31b002ee");
  });

  it("leaves a commander out too", () => {
    const title = buildReplayTitle([
      player("PilotA", "moonshotai/kimi-k3", "max", { commander: "Atraxa, Praetors' Voice" }),
      player("PilotB", "anthropic/claude-fable-5.1", "low"),
    ]);
    expect(title).toBe("KimiK3-max vs Fabl51-low");
  });

  it("disambiguates a self-play game", () => {
    const title = buildReplayTitle([
      player("PilotA", "deepseek/deepseek-v4-pro-0813", "high"),
      player("PilotB", "deepseek/deepseek-v4-pro-0813", "high"),
    ]);
    expect(title).toBe("DSV4P-high-A vs DSV4P-high-B");
  });

  it("keeps the raw seat name for a seat with no model", () => {
    const title = buildReplayTitle([
      { name: "Alex" },
      player("PilotB", "moonshotai/kimi-k3", "max"),
    ]);
    expect(title).toBe("Alex vs KimiK3-max");
  });

  it("handles more than two seats", () => {
    const title = buildReplayTitle([
      player("P1", "moonshotai/kimi-k3", "max"),
      player("P2", "anthropic/claude-fable-5.1", "low"),
      player("P3", "openai/gpt-oss-120b", "medium"),
    ]);
    expect(title).toBe("KimiK3-max vs Fabl51-low vs GptOSS-medium");
  });

  it("is empty for no players rather than throwing", () => {
    expect(buildReplayTitle([])).toBe("");
  });
});
