import { describe, expect, it } from "vitest";

import { drawnCards, openingHands, thumbnailUrl } from "../src/utils/commentary-hands.ts";

function snapshot(turn, step, hands, activePlayer, battlefields = {}) {
  return {
    turn,
    step,
    active_player: activePlayer,
    players: Object.keys(hands).map((name) => ({
      name,
      hand: hands[name].map((card) => ({ name: card })),
      battlefield: (battlefields[name] ?? []).map((card) => ({ name: card })),
    })),
  };
}

const SNAPSHOTS = [
  // Pre-game: nobody has drawn yet.
  snapshot(1, "UNTAP", { PilotA: [], PilotB: [] }, "PilotA"),
  snapshot(1, "UPKEEP", { PilotA: ["Swamp", "Sold Out"], PilotB: ["Plains", "Momo"] }, "PilotA"),
  snapshot(1, "PRECOMBAT_MAIN", { PilotA: ["Swamp", "Sold Out"], PilotB: ["Plains", "Momo"] }, "PilotA"),
  // PilotA plays its Swamp, so its hand shrinks before its next turn.
  snapshot(1, "END_TURN", { PilotA: ["Sold Out"], PilotB: ["Plains", "Momo"] }, "PilotA"),
  snapshot(2, "PRECOMBAT_MAIN", { PilotA: ["Sold Out"], PilotB: ["Plains", "Momo", "Glider Staff"] }, "PilotB"),
  snapshot(2, "END_TURN", { PilotA: ["Sold Out"], PilotB: ["Momo", "Glider Staff"] }, "PilotB"),
  snapshot(3, "PRECOMBAT_MAIN", { PilotA: ["Sold Out", "Mountain"], PilotB: ["Momo", "Glider Staff"] }, "PilotA"),
];

describe("openingHands", () => {
  it("uses the first snapshot where every seat holds cards", () => {
    expect(openingHands(SNAPSHOTS, ["PilotA", "PilotB"])).toEqual({
      PilotA: ["Swamp", "Sold Out"],
      PilotB: ["Plains", "Momo"],
    });
  });

  it("returns nothing when no snapshot has hands for everyone", () => {
    expect(openingHands([snapshot(1, "UNTAP", { PilotA: [], PilotB: [] })], ["PilotA", "PilotB"])).toEqual({});
    expect(openingHands(null, ["PilotA"])).toEqual({});
  });
});

describe("drawnCards", () => {
  it("finds the card gained since the end of that seat's previous turn", () => {
    expect(drawnCards(SNAPSHOTS, 3, "PilotA")).toEqual(["Mountain"]);
  });

  it("shows the first-turn draw for the player on the draw", () => {
    // Turn 2 is that seat's first turn, so there is no previous turn to compare against;
    // measured against the opening hand it is a draw like any other. Skipping it hid
    // Astra drawing Earth Village Ruffians on turn 2 of the published commentary.
    expect(drawnCards(SNAPSHOTS, 2, "PilotB")).toEqual(["Glider Staff"]);
  });

  it("is not fooled by a card cast between the draw and the next turn", () => {
    // PilotB drew Glider Staff on turn 2 and cast Plains before its turn ended; the draw
    // measured against the end of turn 2 must still be the next turn's card, not this one.
    const extended = SNAPSHOTS.concat([
      snapshot(4, "PRECOMBAT_MAIN", { PilotA: ["Sold Out", "Mountain"], PilotB: ["Momo", "Glider Staff", "Swamp"] }, "PilotB"),
    ]);
    expect(drawnCards(extended, 4, "PilotB")).toEqual(["Swamp"]);
  });

  it("ignores a permanent bounced back to hand", () => {
    // Astra's Submersible returned Fable's Warden to hand, which showed up as a second
    // draw alongside the real one.
    const bounced = [
      snapshot(11, "END_TURN", { PilotA: [] }, "PilotA", { PilotA: ["Vindictive Warden"] }),
      snapshot(13, "PRECOMBAT_MAIN", { PilotA: ["Vindictive Warden", "Combustion Technique"] }, "PilotA", { PilotA: [] }),
    ];
    expect(drawnCards(bounced, 13, "PilotA")).toEqual(["Combustion Technique"]);
  });

  it("still counts a card drawn while a copy of it is in play", () => {
    const withCopy = [
      snapshot(11, "END_TURN", { PilotA: [] }, "PilotA", { PilotA: ["Yuyan Archers"] }),
      snapshot(13, "PRECOMBAT_MAIN", { PilotA: ["Yuyan Archers"] }, "PilotA", { PilotA: ["Yuyan Archers"] }),
    ];
    expect(drawnCards(withCopy, 13, "PilotA")).toEqual(["Yuyan Archers"]);
  });

  it("counts a duplicate draw", () => {
    const withDuplicate = SNAPSHOTS.concat([
      snapshot(4, "PRECOMBAT_MAIN", { PilotA: [], PilotB: ["Momo", "Glider Staff", "Momo"] }, "PilotB"),
    ]);
    expect(drawnCards(withDuplicate, 4, "PilotB")).toEqual(["Momo"]);
  });

  it("returns nothing for the player on the play, who skips its first draw", () => {
    expect(drawnCards(SNAPSHOTS, 1, "PilotA")).toEqual([]);
  });

  it("returns nothing for a turn that is not in the game", () => {
    expect(drawnCards(SNAPSHOTS, 99, "PilotA")).toEqual([]);
    expect(drawnCards(null, 3, "PilotA")).toEqual([]);
  });
});

describe("thumbnailUrl", () => {
  it("asks for the small version of a baked image", () => {
    const images = { Swamp: "https://api.scryfall.com/cards/tla/284?format=image&version=normal" };
    expect(thumbnailUrl(images, "Swamp")).toBe(
      "https://api.scryfall.com/cards/tla/284?format=image&version=small",
    );
  });

  it("falls back to Scryfall by name", () => {
    expect(thumbnailUrl({}, "Momo, Playful Pet")).toBe(
      "https://api.scryfall.com/cards/named?exact=Momo%2C%20Playful%20Pet&format=image&version=small",
    );
  });
});
