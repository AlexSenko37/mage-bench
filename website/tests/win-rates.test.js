import { describe, expect, it } from "vitest";

import { computeModelWinRates } from "../src/utils/win-rates.ts";

function game(overrides) {
  return {
    id: "game_1",
    timestamp: "20260101_000000",
    total_turns: 10,
    winner: "A",
    players: [],
    deck_type: "Constructed - Freeform",
    harness_epoch: 1,
    season: 0,
    replayTitle: "A vs B",
    replayBlunderSummary: null,
    errors: [],
    ...overrides,
  };
}

describe("computeModelWinRates", () => {
  it("gives 100%/0% for two distinct models with a clear winner", () => {
    const games = [
      game({
        winner: "A",
        players: [
          { name: "A", type: "pilot", model: "provider/model-x" },
          { name: "B", type: "pilot", model: "provider/model-y" },
        ],
      }),
    ];
    const { rows } = computeModelWinRates(games);
    const x = rows.find((r) => r.model === "provider/model-x");
    const y = rows.find((r) => r.model === "provider/model-y");
    expect(x).toMatchObject({ seats: 1, wins: 1, losses: 0, winRate: 1 });
    expect(y).toMatchObject({ seats: 1, wins: 0, losses: 1, winRate: 0 });
  });

  it("counts self-play as one row with 1 win, 1 loss, 50% win rate", () => {
    const games = [
      game({
        winner: "A",
        players: [
          { name: "A", type: "pilot", model: "provider/model-x" },
          { name: "B", type: "pilot", model: "provider/model-x" },
        ],
      }),
    ];
    const { rows } = computeModelWinRates(games);
    expect(rows).toHaveLength(1);
    expect(rows[0]).toMatchObject({
      model: "provider/model-x",
      seats: 2,
      wins: 1,
      losses: 1,
      winRate: 0.5,
      selfPlaySeats: 2,
    });
  });

  it("excludes a game with no winner and no placement-1 from win/loss tallies", () => {
    const games = [
      game({
        winner: null,
        players: [
          { name: "A", type: "pilot", model: "provider/model-x" },
          { name: "B", type: "pilot", model: "provider/model-y" },
        ],
      }),
    ];
    const { rows, decidedGames, undecidedGames } = computeModelWinRates(games);
    expect(rows).toHaveLength(0);
    expect(decidedGames).toBe(0);
    expect(undecidedGames).toBe(1);
  });

  it("excludes seats with no model (cpu/human) from the tally", () => {
    const games = [
      game({
        winner: "CPU",
        players: [
          { name: "CPU", type: "cpu" },
          { name: "A", type: "pilot", model: "provider/model-x" },
        ],
      }),
    ];
    const { rows } = computeModelWinRates(games);
    expect(rows).toHaveLength(1);
    expect(rows[0]).toMatchObject({ model: "provider/model-x", seats: 1, losses: 1 });
  });

  it("prefers placement over winner when both are present and disagree", () => {
    const games = [
      game({
        winner: "B", // disagrees with placement
        players: [
          { name: "A", type: "pilot", model: "provider/model-x", placement: 1 },
          { name: "B", type: "pilot", model: "provider/model-y", placement: 2 },
        ],
      }),
    ];
    const { rows } = computeModelWinRates(games);
    const x = rows.find((r) => r.model === "provider/model-x");
    expect(x).toMatchObject({ wins: 1, losses: 0 });
  });

  it("returns a zeroed summary for no games, without NaN", () => {
    const { rows, totalGames, decidedGames, undecidedGames } = computeModelWinRates([]);
    expect(rows).toEqual([]);
    expect(totalGames).toBe(0);
    expect(decidedGames).toBe(0);
    expect(undecidedGames).toBe(0);
  });

  it("sorts by win rate desc, then seats desc, then name asc, deterministically", () => {
    const games = [
      game({
        id: "g1",
        winner: "A",
        players: [
          { name: "A", type: "pilot", model: "provider/model-a" },
          { name: "B", type: "pilot", model: "provider/model-b" },
        ],
      }),
      game({
        id: "g2",
        winner: "C",
        players: [
          { name: "C", type: "pilot", model: "provider/model-c" },
          { name: "D", type: "pilot", model: "provider/model-b" },
        ],
      }),
    ];
    // model-a: 1/1 = 100%. model-c: 1/1 = 100%. model-b: 0/2 = 0%.
    const { rows } = computeModelWinRates(games);
    expect(rows.map((r) => r.model)).toEqual(["provider/model-a", "provider/model-c", "provider/model-b"]);
  });
});
