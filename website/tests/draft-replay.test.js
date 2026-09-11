import { describe, expect, it } from "vitest";
import {
  classifyPackCard,
  deckbuildAnalysis,
  deckbuildFallbackReason,
  deckbuildForSeat,
  draftSeats,
  pickLabel,
  picksForSeat,
  summarizeSeat,
  wheeledCount,
} from "../src/scripts/draft-replay.js";

function pick(overrides) {
  return {
    seat: "modelA-A",
    pick_number: 1,
    round: 1,
    pack: "pack1",
    pack_cards: ["Alpha", "Beta", "Gamma"],
    picked: "Alpha",
    picked_index: 0,
    wheeled: [],
    pool_size: 0,
    reasoning: "because",
    elapsed_secs: 2,
    cost_usd: 0.01,
    ...overrides,
  };
}

function draftWith(picks, seats, deckbuild) {
  return {
    seats: seats ?? [
      { seat: "modelA-A", model: "vendor/model-a", picks: picks.length, fallbacks: 0, cost_usd: 0.5 },
    ],
    picks,
    deckbuild: deckbuild ?? [],
  };
}

describe("draftSeats", () => {
  it("returns nothing for a game with no draft", () => {
    expect(draftSeats(null)).toEqual([]);
    expect(draftSeats(undefined)).toEqual([]);
    expect(draftSeats({})).toEqual([]);
  });

  it("omits seats that made no picks", () => {
    const draft = draftWith(
      [],
      [
        { seat: "a", model: "m", picks: 3, fallbacks: 0, cost_usd: 0 },
        { seat: "b", model: "m", picks: 0, fallbacks: 0, cost_usd: 0 },
      ],
    );
    expect(draftSeats(draft).map((s) => s.seat)).toEqual(["a"]);
  });
});

describe("picksForSeat", () => {
  it("filters by seat and orders by pick number", () => {
    const draft = draftWith([
      pick({ pick_number: 3 }),
      pick({ pick_number: 1 }),
      pick({ seat: "other-B", pick_number: 2 }),
      pick({ pick_number: 2 }),
    ]);
    expect(picksForSeat(draft, "modelA-A").map((p) => p.pick_number)).toEqual([1, 2, 3]);
  });

  it("is empty for an unknown seat", () => {
    expect(picksForSeat(draftWith([pick({})]), "nobody")).toEqual([]);
  });
});

describe("pickLabel", () => {
  it("numbers picks within their own booster round", () => {
    expect(pickLabel(pick({ round: 1, pick_number: 1 }))).toBe("P1p1");
    expect(pickLabel(pick({ round: 1, pick_number: 14 }))).toBe("P1p14");
    expect(pickLabel(pick({ round: 2, pick_number: 15 }))).toBe("P2p1");
    expect(pickLabel(pick({ round: 3, pick_number: 42 }))).toBe("P3p14");
  });

  it("honours a different pack size", () => {
    expect(pickLabel(pick({ round: 2, pick_number: 9 }), 8)).toBe("P2p1");
  });
});

describe("classifyPackCard", () => {
  it("marks the taken card", () => {
    expect(classifyPackCard(pick({ picked_index: 1 }), 1)).toBe("picked");
  });

  it("marks cards seen on an earlier pass as wheeled", () => {
    const p = pick({ picked_index: 0, wheeled: ["Gamma"] });
    expect(classifyPackCard(p, 2)).toBe("wheeled");
  });

  it("marks everything else passed", () => {
    expect(classifyPackCard(pick({ picked_index: 0 }), 1)).toBe("passed");
  });

  it("prefers picked over wheeled when a wheeled card is finally taken", () => {
    // A card can wheel and then be the pick; showing it as merely wheeled would hide
    // the actual decision.
    const p = pick({ picked: "Gamma", picked_index: 2, wheeled: ["Gamma"] });
    expect(classifyPackCard(p, 2)).toBe("picked");
  });

  it("treats a null picked_index as nothing picked", () => {
    const p = pick({ picked: null, picked_index: null });
    expect(classifyPackCard(p, 0)).toBe("passed");
  });
});

describe("wheeledCount", () => {
  it("counts wheeled cards", () => {
    expect(wheeledCount(pick({ wheeled: ["a", "b"] }))).toBe(2);
    expect(wheeledCount(pick({ wheeled: [] }))).toBe(0);
  });
});

describe("summarizeSeat", () => {
  it("summarises picks, cost and wheels", () => {
    const draft = draftWith(
      [
        pick({ pick_number: 1, elapsed_secs: 2, wheeled: [] }),
        pick({ pick_number: 2, elapsed_secs: 4, wheeled: ["x"] }),
        pick({ pick_number: 3, elapsed_secs: 6, wheeled: ["y"] }),
      ],
      [{ seat: "modelA-A", model: "vendor/model-a", picks: 3, fallbacks: 2, cost_usd: 1.25 }],
    );
    const s = summarizeSeat(draft, "modelA-A");
    expect(s.picks).toBe(3);
    expect(s.model).toBe("vendor/model-a");
    expect(s.fallbacks).toBe(2);
    expect(s.costUsd).toBe(1.25);
    expect(s.wheelPicks).toBe(2);
    expect(s.medianElapsed).toBe(4);
  });

  it("reports zero reasoning picks when the model returned no traces", () => {
    // Fable 5.1 at low effort reports reasoning_tokens: 0, so this is the normal case for
    // a low-effort seat rather than a capture failure -- the view says so explicitly.
    const draft = draftWith([pick({ reasoning: "" }), pick({ pick_number: 2, reasoning: "   " })]);
    expect(summarizeSeat(draft, "modelA-A").reasoningPicks).toBe(0);
  });

  it("counts partial reasoning coverage", () => {
    const draft = draftWith([pick({ reasoning: "why" }), pick({ pick_number: 2, reasoning: "" })]);
    expect(summarizeSeat(draft, "modelA-A").reasoningPicks).toBe(1);
  });

  it("has a null median when no pick recorded a duration", () => {
    const draft = draftWith([pick({ elapsed_secs: null })]);
    expect(summarizeSeat(draft, "modelA-A").medianElapsed).toBeNull();
  });

  it("averages the middle two for an even number of picks", () => {
    const draft = draftWith([
      pick({ pick_number: 1, elapsed_secs: 1 }),
      pick({ pick_number: 2, elapsed_secs: 2 }),
      pick({ pick_number: 3, elapsed_secs: 3 }),
      pick({ pick_number: 4, elapsed_secs: 10 }),
    ]);
    expect(summarizeSeat(draft, "modelA-A").medianElapsed).toBe(2.5);
  });
});

describe("deckbuildForSeat", () => {
  it("returns only that seat's steps, in order", () => {
    const draft = draftWith(
      [pick({})],
      undefined,
      [
        { seat: "modelA-A", stage: "spells", content: "{}", reasoning: "", cost_usd: 0.1 },
        { seat: "other-B", stage: "spells", content: "{}", reasoning: "", cost_usd: 0.1 },
        { seat: "modelA-A", stage: "lands", content: "{}", reasoning: "", cost_usd: 0.2 },
      ],
    );
    expect(deckbuildForSeat(draft, "modelA-A").map((s) => s.stage)).toEqual(["spells", "lands"]);
  });

  it("is empty when the game has no deckbuild record", () => {
    expect(deckbuildForSeat({}, "modelA-A")).toEqual([]);
  });
});

describe("deckbuildAnalysis", () => {
  it("pulls the model's prose out of the JSON reply", () => {
    const step = { content: JSON.stringify({ analysis: "UB tempo", chosen_spells: ["a"] }) };
    expect(deckbuildAnalysis(step)).toBe("UB tempo");
  });

  it("shows an unparseable reply verbatim rather than dropping it", () => {
    // A deckbuild answer that will not parse is itself the interesting finding.
    expect(deckbuildAnalysis({ content: "not json at all" })).toBe("not json at all");
  });

  it("falls through to the raw reply when there is no analysis field", () => {
    expect(deckbuildAnalysis({ content: '{"land_counts":{}}' })).toBe('{"land_counts":{}}');
  });

  it("is empty for an empty reply", () => {
    expect(deckbuildAnalysis({ content: "" })).toBe("");
  });
});

describe("deckbuildFallbackReason", () => {
  it("is null when the model's own answer was used", () => {
    const draft = draftWith([pick({})], undefined, [
      { seat: "modelA-A", stage: "spells", content: "{}", reasoning: "", cost_usd: 0.1 },
      { seat: "modelA-A", stage: "lands", content: "{}", reasoning: "", cost_usd: 0.1 },
    ]);
    expect(deckbuildFallbackReason(draft, "modelA-A")).toBeNull();
  });

  it("returns the reason when the heuristic built the deck", () => {
    // The picks can be entirely the model's while the 40 cards played are RateCard's,
    // and the decklist alone gives no hint of that. gpt-oss did exactly this on a real
    // validation draft while its per-seat summary still read "0 fallbacks".
    const draft = draftWith([pick({})], undefined, [
      { seat: "modelA-A", stage: "spells", content: "{}", reasoning: "", cost_usd: 0.1 },
      {
        seat: "modelA-A",
        stage: "deckbuild_fallback",
        content: "no usable spell list after 3 rounds",
        reasoning: "",
        cost_usd: 0,
      },
    ]);
    expect(deckbuildFallbackReason(draft, "modelA-A")).toBe("no usable spell list after 3 rounds");
  });

  it("does not leak one seat's fallback onto another", () => {
    const draft = draftWith([pick({})], undefined, [
      { seat: "other-B", stage: "deckbuild_fallback", content: "broke", reasoning: "", cost_usd: 0 },
    ]);
    expect(deckbuildFallbackReason(draft, "modelA-A")).toBeNull();
    expect(deckbuildFallbackReason(draft, "other-B")).toBe("broke");
  });

  it("is null for a game with no deckbuild record at all", () => {
    expect(deckbuildFallbackReason({}, "modelA-A")).toBeNull();
  });
});
