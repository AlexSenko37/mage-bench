import { describe, expect, it } from "vitest";

import { parseDecklistLine } from "../src/utils/decklist.ts";

describe("parseDecklistLine", () => {
  it("parses a normal mainboard line", () => {
    expect(parseDecklistLine("1 [TLA:146] Lightning Strike")).toEqual({
      count: 1,
      name: "Lightning Strike",
      set: "TLA",
      num: "146",
      sideboard: false,
    });
  });

  it("parses a multi-copy line", () => {
    expect(parseDecklistLine("4 [EOE:251] Breeding Pool")).toEqual({
      count: 4,
      name: "Breeding Pool",
      set: "EOE",
      num: "251",
      sideboard: false,
    });
  });

  it("marks an SB: prefixed line as sideboard", () => {
    expect(parseDecklistLine("SB: 1 [TLA:1] Commander")).toEqual({
      count: 1,
      name: "Commander",
      set: "TLA",
      num: "1",
      sideboard: true,
    });
  });

  it("returns null for the NAME: header line", () => {
    expect(parseDecklistLine("NAME:Generated-Deck-09-08-2026-07-33-27-531")).toBeNull();
  });

  it("returns null for an empty line", () => {
    expect(parseDecklistLine("")).toBeNull();
  });

  it("returns null for a line with no set/collector bracket", () => {
    expect(parseDecklistLine("1 Lightning Strike")).toBeNull();
  });
});
