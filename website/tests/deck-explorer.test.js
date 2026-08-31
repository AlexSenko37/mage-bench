import { describe, expect, it } from "vitest";

import {
  buildDeckCards,
  computeDeckStats,
  groupDeck,
  parseManaCost,
  primaryType,
  renderDeckGrid,
} from "../src/scripts/deck-explorer.js";

describe("parseManaCost", () => {
  it("returns zero cmc and no colors for lands", () => {
    expect(parseManaCost("")).toEqual({ cmc: 0, colors: [], hasX: false });
  });

  it("parses generic + colored mana", () => {
    expect(parseManaCost("{2}{B}")).toEqual({ cmc: 3, colors: ["B"], hasX: false });
  });

  it("treats X as contributing zero to cmc but flags hasX", () => {
    expect(parseManaCost("{X}{R}{R}{R}")).toEqual({ cmc: 3, colors: ["R"], hasX: true });
  });

  it("counts hybrid mana as 1 and both colors", () => {
    const result = parseManaCost("{B/R}");
    expect(result.cmc).toBe(1);
    expect(result.colors.sort()).toEqual(["B", "R"]);
  });

  it("counts monocolor hybrid by its generic number", () => {
    expect(parseManaCost("{2/W}{2/W}")).toEqual({ cmc: 4, colors: ["W"], hasX: false });
  });

  it("counts phyrexian mana as 1 and its color", () => {
    expect(parseManaCost("{B/P}")).toEqual({ cmc: 1, colors: ["B"], hasX: false });
  });

  it("counts colorless pips toward cmc with no color", () => {
    expect(parseManaCost("{C}{C}")).toEqual({ cmc: 2, colors: [], hasX: false });
  });

  it("only looks at the front face of a DFC/split cost", () => {
    expect(parseManaCost("{2}{R} // {1}{B}")).toEqual({ cmc: 3, colors: ["R"], hasX: false });
  });
});

describe("primaryType", () => {
  it("prefers Land over Artifact for artifact lands", () => {
    expect(primaryType("Artifact Land")).toBe("Land");
  });

  it("prefers Creature over Artifact for artifact creatures", () => {
    expect(primaryType("Artifact Creature — Golem")).toBe("Creature");
  });

  it("reads past the subtype divider", () => {
    expect(primaryType("Legendary Creature — Human Rogue Ally")).toBe("Creature");
  });

  it("recognizes a plain enchantment", () => {
    expect(primaryType("Enchantment")).toBe("Enchantment");
  });

  it("falls back to Other for an unrecognized/empty type line", () => {
    expect(primaryType("")).toBe("Other");
  });

  it("only looks at the front face for split types", () => {
    expect(primaryType("Instant // Sorcery")).toBe("Instant");
  });
});

const CARD_DATA = {
  "Lightning Strike": { mana_cost: "{1}{R}", type_line: "Instant" },
  "Boiling Rock Rioter": { mana_cost: "{2}{B}", type_line: "Creature — Human Rogue Ally" },
  Mountain: { mana_cost: "", type_line: "Basic Land — Mountain" },
  "Some Gold Card": { mana_cost: "{B/R}", type_line: "Creature — Horror" },
};

const DECKLIST = [
  "NAME:Generated-Deck-09-08-2026-07-33-27-531",
  "1 [TLA:146] Lightning Strike",
  "1 [TLA:1] Boiling Rock Rioter",
  "5 [TLA:295] Mountain",
  "SB: 1 [TLA:9] Some Gold Card",
];

describe("buildDeckCards", () => {
  it("skips the NAME header line and sideboard entries", () => {
    const cards = buildDeckCards(DECKLIST, CARD_DATA);
    expect(cards.map((c) => c.name)).toEqual(["Lightning Strike", "Boiling Rock Rioter", "Mountain"]);
  });

  it("still renders a card missing from card_data instead of dropping it", () => {
    const cards = buildDeckCards(["1 [TLA:999] Unknown Card"], CARD_DATA);
    expect(cards).toHaveLength(1);
    expect(cards[0]).toMatchObject({ name: "Unknown Card", cmc: 0, colors: [], type: "Other", isLand: false });
  });

  it("derives cmc/colors/type/isLand from card_data", () => {
    const cards = buildDeckCards(DECKLIST, CARD_DATA);
    const strike = cards.find((c) => c.name === "Lightning Strike");
    expect(strike).toMatchObject({ count: 1, cmc: 2, colors: ["R"], type: "Instant", isLand: false });
    const mountain = cards.find((c) => c.name === "Mountain");
    expect(mountain).toMatchObject({ count: 5, cmc: 0, colors: [], type: "Land", isLand: true });
  });
});

describe("groupDeck", () => {
  const cards = buildDeckCards(
    [
      "1 [TLA:146] Lightning Strike", // cmc 2, R, Instant
      "1 [TLA:1] Boiling Rock Rioter", // cmc 3, B, Creature
      "1 [TLA:9] Some Gold Card", // cmc 1, B/R, Creature
      "14 [TLA:295] Mountain", // land
    ],
    CARD_DATA,
  );

  it("groups by mana value with lands in a trailing column, deduped with a count", () => {
    const groups = groupDeck(cards, "cmc");
    const labels = groups.map((g) => g.label);
    expect(labels).toEqual(["1", "2", "3", "Lands"]);
    const landsGroup = groups.find((g) => g.label === "Lands");
    expect(landsGroup.cards).toEqual([expect.objectContaining({ name: "Mountain", count: 14 })]);
  });

  it("buckets 7+ mana value into one trailing column before lands", () => {
    const bigCard = buildDeckCards(["1 [X:1] Big Thing"], { "Big Thing": { mana_cost: "{9}", type_line: "Creature" } });
    const groups = groupDeck(bigCard, "cmc");
    expect(groups.map((g) => g.label)).toEqual(["7+"]);
  });

  it("groups by color (WUBRG order), putting a hybrid card in Multicolor", () => {
    const groups = groupDeck(cards, "color");
    const labels = groups.map((g) => g.label);
    expect(labels).toEqual(["Black", "Red", "Multicolor", "Lands"]);
  });

  it("groups a colorless card into Colorless", () => {
    const colorless = buildDeckCards(["1 [X:1] Colorless Thing"], {
      "Colorless Thing": { mana_cost: "{2}", type_line: "Artifact" },
    });
    const groups = groupDeck(colorless, "color");
    expect(groups.map((g) => g.label)).toEqual(["Colorless"]);
  });

  it("groups by card type", () => {
    const groups = groupDeck(cards, "type");
    expect(groups.map((g) => g.label)).toEqual(["Land", "Creature", "Instant"]);
  });
});

describe("computeDeckStats", () => {
  const cards = buildDeckCards(
    [
      "1 [TLA:146] Lightning Strike", // cmc 2, R
      "1 [TLA:9] Some Gold Card", // cmc 1, B/R
      "14 [TLA:295] Mountain", // land
    ],
    CARD_DATA,
  );
  const stats = computeDeckStats(cards);

  it("sums total copies across cards", () => {
    expect(stats.total).toBe(16);
    expect(stats.unique).toBe(3);
  });

  it("excludes lands from the mana curve", () => {
    const nonZero = stats.curve.filter((b) => b.count > 0);
    expect(nonZero).toEqual([
      { bucket: "1", count: 1 },
      { bucket: "2", count: 1 },
    ]);
  });

  it("counts a gold card once per color it contains, plus once in multicolor", () => {
    // Lightning Strike is R; Some Gold Card is B/R -- so R is touched by both cards.
    expect(stats.byColor.R).toBe(2);
    expect(stats.byColor.B).toBe(1);
    expect(stats.byColor.multicolor).toBe(1);
  });

  it("counts card types including lands", () => {
    expect(stats.byType).toEqual({ Land: 14, Creature: 1, Instant: 1 });
  });
});

describe("renderDeckGrid", () => {
  it("builds one .deck-col per group with a card thumbnail per card", () => {
    const container = document.createElement("div");
    const fakeRenderer = {
      makeCardThumbnail: (name) => {
        const el = document.createElement("div");
        el.className = "card-thumb";
        el.textContent = name;
        return el;
      },
    };
    const groups = [
      { key: "1", label: "1", cards: [{ name: "Card A", count: 1 }] },
      { key: "lands", label: "Lands", cards: [{ name: "Mountain", count: 5 }] },
    ];

    renderDeckGrid(container, groups, { renderer: fakeRenderer, cardData: {}, cardImages: {}, previewEls: {} });

    const columns = container.querySelectorAll(".deck-col");
    expect(columns).toHaveLength(2);
    expect(columns[0].querySelector(".deck-col-label").textContent).toBe("1");
    expect(columns[0].querySelector(".deck-col-count").textContent).toBe("1");
    expect(columns[1].querySelector(".card-count-badge").textContent).toBe("×5");
  });
});
