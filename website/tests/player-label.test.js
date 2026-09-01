import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";

import { MODEL_SHORT_NAMES, modelShortName, playerDisplayLabel } from "../src/scripts/player-label.js";

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
