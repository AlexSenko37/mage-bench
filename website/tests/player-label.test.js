import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";

import {
  MODEL_SHORT_NAMES,
  buildPlayerLabelMap,
  labelFor,
  modelShortName,
  playerDisplayLabel,
} from "../src/scripts/player-label.js";

const here = dirname(fileURLToPath(import.meta.url));
const MODELS_JSON = resolve(here, "../../puppeteer/models.json");

describe("MODEL_SHORT_NAMES", () => {
  it("stays in sync with name_part in puppeteer/models.json", () => {
    // models.json is the source of truth; player-label.js mirrors it so the browser
    // bundle doesn't have to reach outside the website root. If a model is added there
    // and not here, the replay silently falls back to a raw id -- so fail loudly instead.
    const models = JSON.parse(readFileSync(MODELS_JSON, "utf-8")).models;
    const expected = {};
    for (const m of models) {
      if (m.name_part) expected[m.id] = m.name_part;
    }
    expect(MODEL_SHORT_NAMES).toEqual(expected);
  });
});

describe("modelShortName", () => {
  it("maps a known model id to its short name", () => {
    expect(modelShortName("anthropic/claude-fable-5")).toBe("Fable5");
    expect(modelShortName("moonshotai/kimi-k3")).toBe("KimiK3");
  });

  it("falls back to the id without its provider prefix when unknown", () => {
    expect(modelShortName("someco/brand-new-model")).toBe("brand-new-model");
  });

  it("handles an unknown id with no provider prefix", () => {
    expect(modelShortName("bare-model-id")).toBe("bare-model-id");
  });

  it("returns empty string for a missing id", () => {
    expect(modelShortName(undefined)).toBe("");
    expect(modelShortName("")).toBe("");
  });
});

describe("playerDisplayLabel", () => {
  it("combines short name and reasoning effort", () => {
    expect(playerDisplayLabel({
      name: "PilotA",
      model: "anthropic/claude-fable-5",
      reasoning_effort: "low",
    })).toBe("Fable5-low");
    expect(playerDisplayLabel({
      name: "PilotB",
      model: "moonshotai/kimi-k3",
      reasoning_effort: "max",
    })).toBe("KimiK3-max");
  });

  it("omits the effort suffix when the model has no reasoning effort", () => {
    expect(playerDisplayLabel({ name: "PilotA", model: "deepseek/deepseek-v3.2" })).toBe("DSV3");
  });

  it("falls back to the seat name for a seat with no model (human/CPU)", () => {
    expect(playerDisplayLabel({ name: "Human" })).toBe("Human");
  });

  it("returns empty string for a missing player", () => {
    expect(playerDisplayLabel(null)).toBe("");
  });
});

describe("buildPlayerLabelMap", () => {
  it("maps each seat to its model and effort", () => {
    const map = buildPlayerLabelMap([
      { name: "PilotA", model: "anthropic/claude-fable-5.1", reasoning_effort: "low" },
      { name: "PilotB", model: "moonshotai/kimi-k3", reasoning_effort: "max" },
    ]);
    expect(map).toEqual({ PilotA: "Fabl51-low", PilotB: "KimiK3-max" });
  });

  it("suffixes -A/-B only when two seats would collide", () => {
    // Self-play runs the same model at the same effort on both sides, so the labels are
    // identical and something has to tell them apart.
    const map = buildPlayerLabelMap([
      { name: "PilotA", model: "deepseek/deepseek-v4-pro-0813", reasoning_effort: "high" },
      { name: "PilotB", model: "deepseek/deepseek-v4-pro-0813", reasoning_effort: "high" },
    ]);
    expect(map).toEqual({ PilotA: "DSV4P-high-A", PilotB: "DSV4P-high-B" });
  });

  it("leaves a unique label unsuffixed even when another seat collides", () => {
    const map = buildPlayerLabelMap([
      { name: "P1", model: "moonshotai/kimi-k3", reasoning_effort: "max" },
      { name: "P2", model: "moonshotai/kimi-k3", reasoning_effort: "max" },
      { name: "P3", model: "anthropic/claude-fable-5.1", reasoning_effort: "low" },
    ]);
    expect(map.P3).toBe("Fabl51-low");
    expect(map.P1).toBe("KimiK3-max-A");
    expect(map.P2).toBe("KimiK3-max-B");
  });

  it("distinguishes the same model at different efforts without a suffix", () => {
    const map = buildPlayerLabelMap([
      { name: "PilotA", model: "moonshotai/kimi-k3", reasoning_effort: "low" },
      { name: "PilotB", model: "moonshotai/kimi-k3", reasoning_effort: "max" },
    ]);
    expect(map).toEqual({ PilotA: "KimiK3-low", PilotB: "KimiK3-max" });
  });

  it("keeps the raw seat name for a seat with no model", () => {
    const map = buildPlayerLabelMap([
      { name: "Human1" },
      { name: "PilotB", model: "moonshotai/kimi-k3", reasoning_effort: "max" },
    ]);
    expect(map).toEqual({ Human1: "Human1", PilotB: "KimiK3-max" });
  });

  it("handles an empty or missing player list", () => {
    expect(buildPlayerLabelMap([])).toEqual({});
    expect(buildPlayerLabelMap(null)).toEqual({});
    expect(buildPlayerLabelMap(undefined)).toEqual({});
  });

  it("skips entries with no name rather than keying on undefined", () => {
    const map = buildPlayerLabelMap([{ model: "moonshotai/kimi-k3" }, { name: "PilotB" }]);
    expect(Object.keys(map)).toEqual(["PilotB"]);
  });

  it("falls back to the model id for a model missing from the short-name table", () => {
    const map = buildPlayerLabelMap([{ name: "PilotA", model: "newvendor/brand-new-model" }]);
    expect(map.PilotA).toBe("brand-new-model");
  });
});

describe("labelFor", () => {
  it("returns the mapped label", () => {
    expect(labelFor({ PilotA: "KimiK3-max" }, "PilotA")).toBe("KimiK3-max");
  });

  it("falls back to the raw name when unmapped, so a name never renders blank", () => {
    expect(labelFor({ PilotA: "KimiK3-max" }, "Spectator")).toBe("Spectator");
    expect(labelFor(null, "PilotA")).toBe("PilotA");
    expect(labelFor({}, "PilotA")).toBe("PilotA");
  });
});
