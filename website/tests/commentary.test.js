import { describe, expect, it } from "vitest";

import {
  commentaryCardNames,
  findTurnSnapshotIndex,
  parseCommentaryText,
} from "../src/utils/commentary.ts";

describe("parseCommentaryText", () => {
  it("returns a single text segment when there are no markers", () => {
    expect(parseCommentaryText("Fable goes first.")).toEqual([
      { kind: "text", text: "Fable goes first." },
    ]);
  });

  it("reads a card marker and keeps the surrounding text", () => {
    expect(parseCommentaryText("plays [[card:Glider Staff]] now")).toEqual([
      { kind: "text", text: "plays " },
      { kind: "card", text: "Glider Staff", card: "Glider Staff" },
      { kind: "text", text: " now" },
    ]);
  });

  it("uses the label after the pipe as the link text", () => {
    expect(parseCommentaryText("[[card:Momo, Playful Pet|Momo]] attacks")).toEqual([
      { kind: "card", text: "Momo", card: "Momo, Playful Pet" },
      { kind: "text", text: " attacks" },
    ]);
  });

  it("reads a player marker as a seat plus its display name", () => {
    expect(parseCommentaryText("[[player:PilotA|Fable]] passes")).toEqual([
      { kind: "player", text: "Fable", seat: "PilotA" },
      { kind: "text", text: " passes" },
    ]);
  });

  it("leaves a malformed marker as literal text rather than dropping the sentence", () => {
    const text = "a [[card Glider Staff]] typo";
    expect(parseCommentaryText(text)).toEqual([{ kind: "text", text }]);
  });

  it("handles several markers in one paragraph", () => {
    const segments = parseCommentaryText(
      "[[player:PilotB|Astra]] casts [[card:Sold Out]] on [[card:Momo, Playful Pet|Momo]].",
    );
    expect(segments.map((s) => s.kind)).toEqual(["player", "text", "card", "text", "card", "text"]);
  });
});

describe("commentaryCardNames", () => {
  it("lists each card once, in first-mention order", () => {
    const commentary = {
      gameId: "g1",
      rounds: [
        {
          label: "Turn 1",
          gameTurns: { PilotA: 1 },
          paragraphs: ["[[card:Swamp]] then [[card:Momo, Playful Pet|Momo]]"],
        },
        {
          label: "Turn 2",
          gameTurns: { PilotA: 3 },
          paragraphs: ["[[card:Momo, Playful Pet|Momo]] again, then [[card:Plains]]"],
        },
      ],
    };
    expect(commentaryCardNames(commentary)).toEqual(["Swamp", "Momo, Playful Pet", "Plains"]);
  });
});

describe("findTurnSnapshotIndex", () => {
  const snapshots = [
    { turn: 1, active_player: "PilotA" },
    { turn: 2, active_player: "PilotB" },
    { turn: 2, active_player: "PilotB" },
    { turn: 3, active_player: "PilotA" },
  ];

  it("finds the first snapshot of the turn for that seat", () => {
    expect(findTurnSnapshotIndex(snapshots, 2, "PilotB")).toBe(1);
  });

  it("falls back to the first snapshot of the turn when the seat never has priority", () => {
    expect(findTurnSnapshotIndex(snapshots, 2, "PilotA")).toBe(1);
  });

  it("works without a seat", () => {
    expect(findTurnSnapshotIndex(snapshots, 3)).toBe(3);
  });

  it("returns null for a turn that is not in the game", () => {
    expect(findTurnSnapshotIndex(snapshots, 9, "PilotA")).toBeNull();
    expect(findTurnSnapshotIndex(null, 1)).toBeNull();
  });
});
