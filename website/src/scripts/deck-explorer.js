// Deck explorer: per-player decklist view for the game replay page.
//
// Pure ESM exports (not the IIFE/window.* style of game-renderer.js) so the logic here is
// directly unit-testable. DOM building (renderDeckGrid) takes a GameRenderer instance and
// preview elements as parameters rather than reaching for globals.

import { parseDecklistLine } from "../utils/decklist.ts";

var WUBRG = ["W", "U", "B", "R", "G"];

/**
 * Parse a Scryfall-style mana_cost string (e.g. "{2}{B}") into converted mana cost and
 * the set of colors it contributes. Known limitation: this only sees the mana cost, so it
 * misses devoid/color-indicator cards and land color identity -- acceptable here, not
 * silently "fixed" with heuristics.
 */
export function parseManaCost(manaCost) {
  var front = String(manaCost || "").split("//")[0];
  var colorSet = {};
  var cmc = 0;
  var hasX = false;

  var symbolRe = /\{([^}]+)\}/g;
  var match;
  while ((match = symbolRe.exec(front)) !== null) {
    var sym = match[1].toUpperCase();

    if (/^\d+$/.test(sym)) {
      cmc += Number(sym);
      continue;
    }
    if (sym === "X" || sym === "Y" || sym === "Z") {
      hasX = true;
      continue;
    }
    if (sym === "C" || sym === "S") {
      // Colorless pip or snow pip: contributes to cmc but not color.
      cmc += 1;
      continue;
    }

    if (sym.indexOf("/") !== -1) {
      var parts = sym.split("/");
      if (parts.indexOf("P") !== -1) {
        // Phyrexian, e.g. {B/P}
        cmc += 1;
      } else if (/^\d+$/.test(parts[0])) {
        // Monocolor hybrid, e.g. {2/W}
        cmc += Number(parts[0]);
      } else {
        // Hybrid, e.g. {B/R}
        cmc += 1;
      }
      for (var p = 0; p < parts.length; p++) {
        if (WUBRG.indexOf(parts[p]) !== -1) colorSet[parts[p]] = true;
      }
      continue;
    }

    if (WUBRG.indexOf(sym) !== -1) {
      cmc += 1;
      colorSet[sym] = true;
      continue;
    }
    // Unknown symbol (e.g. a half-mana or infinity symbol from some un-set): ignored.
  }

  return { cmc: cmc, colors: Object.keys(colorSet), hasX: hasX };
}

var TYPE_ORDER = ["Land", "Creature", "Artifact", "Enchantment", "Planeswalker", "Battle", "Instant", "Sorcery"];

/** Extract the primary card type from a type line, front face only. */
export function primaryType(typeLine) {
  var front = String(typeLine || "").split("//")[0].split("—")[0];
  for (var i = 0; i < TYPE_ORDER.length; i++) {
    if (front.indexOf(TYPE_ORDER[i]) !== -1) return TYPE_ORDER[i];
  }
  return "Other";
}

/**
 * Build the list of deck cards (mainboard only) from raw decklist lines, joined against
 * card_data for mana cost / type / colors. A card missing from card_data still renders --
 * card_images is expected to be complete, so only the stats/grouping degrade, not the card
 * itself.
 */
export function buildDeckCards(decklistLines, cardData) {
  var cards = [];
  (decklistLines || []).forEach(function (line) {
    var entry = parseDecklistLine(line);
    if (entry == null || entry.sideboard) return;

    var meta = (cardData && cardData[entry.name]) || null;
    var manaCost = meta ? meta.mana_cost : "";
    var typeLine = meta ? meta.type_line : "";
    var parsed = parseManaCost(manaCost);
    var type = primaryType(typeLine);

    cards.push({
      name: entry.name,
      count: entry.count,
      set: entry.set,
      num: entry.num,
      manaCost: manaCost,
      typeLine: typeLine,
      cmc: parsed.cmc,
      colors: parsed.colors,
      type: type,
      isLand: type === "Land",
    });
  });
  return cards;
}

function dedupeLands(cards) {
  var byName = {};
  var order = [];
  cards.forEach(function (card) {
    if (!byName[card.name]) {
      byName[card.name] = { name: card.name, count: 0, cmc: card.cmc, colors: card.colors, type: card.type, isLand: true };
      order.push(card.name);
    }
    byName[card.name].count += card.count;
  });
  return order.map(function (name) {
    return byName[name];
  });
}

function sortByCmcThenName(a, b) {
  if (a.cmc !== b.cmc) return a.cmc - b.cmc;
  return a.name.localeCompare(b.name);
}

/** Group deck cards by mana value: columns 0-6, a trailing 7+, then a Lands column. */
function groupByCmc(cards) {
  var nonLands = cards.filter(function (c) { return !c.isLand; });
  var lands = dedupeLands(cards.filter(function (c) { return c.isLand; }));

  var buckets = {};
  nonLands.forEach(function (card) {
    var key = String(Math.min(Math.floor(card.cmc), 7));
    (buckets[key] = buckets[key] || []).push(card);
  });

  var groups = [];
  for (var i = 0; i <= 7; i++) {
    var key = String(i);
    if (buckets[key] && buckets[key].length) {
      groups.push({
        key: key,
        label: i === 7 ? "7+" : key,
        cards: buckets[key].slice().sort(sortByCmcThenName),
      });
    }
  }
  if (lands.length) {
    groups.push({ key: "lands", label: "Lands", cards: lands.slice().sort(function (a, b) { return a.name.localeCompare(b.name); }) });
  }
  return groups;
}

var COLOR_ORDER = [
  { key: "W", label: "White" },
  { key: "U", label: "Blue" },
  { key: "B", label: "Black" },
  { key: "R", label: "Red" },
  { key: "G", label: "Green" },
];

/** Group deck cards by color: one bucket per color, then Multicolor, Colorless, Lands. */
function groupByColor(cards) {
  var buckets = { multi: [], colorless: [], lands: dedupeLands(cards.filter(function (c) { return c.isLand; })) };
  COLOR_ORDER.forEach(function (c) { buckets[c.key] = []; });

  cards.filter(function (c) { return !c.isLand; }).forEach(function (card) {
    if (card.colors.length >= 2) {
      buckets.multi.push(card);
    } else if (card.colors.length === 0) {
      buckets.colorless.push(card);
    } else {
      buckets[card.colors[0]].push(card);
    }
  });

  var groups = [];
  COLOR_ORDER.forEach(function (c) {
    if (buckets[c.key].length) groups.push({ key: c.key, label: c.label, cards: buckets[c.key].slice().sort(sortByCmcThenName) });
  });
  if (buckets.multi.length) groups.push({ key: "multi", label: "Multicolor", cards: buckets.multi.slice().sort(sortByCmcThenName) });
  if (buckets.colorless.length) groups.push({ key: "colorless", label: "Colorless", cards: buckets.colorless.slice().sort(sortByCmcThenName) });
  if (buckets.lands.length) groups.push({ key: "lands", label: "Lands", cards: buckets.lands.slice().sort(function (a, b) { return a.name.localeCompare(b.name); }) });
  return groups;
}

/** Group deck cards by primary card type, in TYPE_ORDER, then Other. */
function groupByType(cards) {
  var buckets = {};
  cards.forEach(function (card) {
    (buckets[card.type] = buckets[card.type] || []).push(card);
  });

  var order = TYPE_ORDER.concat(["Other"]);
  var groups = [];
  order.forEach(function (type) {
    if (buckets[type] && buckets[type].length) {
      var sorted = type === "Land"
        ? dedupeLands(buckets[type]).sort(function (a, b) { return a.name.localeCompare(b.name); })
        : buckets[type].slice().sort(sortByCmcThenName);
      groups.push({ key: type, label: type, cards: sorted });
    }
  });
  return groups;
}

export var GROUP_MODES = [
  { key: "cmc", label: "Mana Value" },
  { key: "color", label: "Color" },
  { key: "type", label: "Card Type" },
];

/** Group deck cards by the named mode: "cmc" | "color" | "type". */
export function groupDeck(cards, mode) {
  if (mode === "color") return groupByColor(cards);
  if (mode === "type") return groupByType(cards);
  return groupByCmc(cards);
}

/** Compute summary stats for a deck: totals, color/type breakdown, and a mana curve. */
export function computeDeckStats(cards) {
  var total = 0;
  var unique = cards.length;
  var byColor = { W: 0, U: 0, B: 0, R: 0, G: 0, colorless: 0, multicolor: 0 };
  var byType = {};
  var curveBuckets = {};

  cards.forEach(function (card) {
    total += card.count;

    if (!card.isLand) {
      if (card.colors.length >= 2) {
        byColor.multicolor += card.count;
      } else if (card.colors.length === 0) {
        byColor.colorless += card.count;
      }
      card.colors.forEach(function (c) {
        byColor[c] += card.count;
      });

      var key = String(Math.min(Math.floor(card.cmc), 7));
      curveBuckets[key] = (curveBuckets[key] || 0) + card.count;
    }

    byType[card.type] = (byType[card.type] || 0) + card.count;
  });

  var curve = [];
  for (var i = 0; i <= 7; i++) {
    var key = String(i);
    curve.push({ bucket: i === 7 ? "7+" : key, count: curveBuckets[key] || 0 });
  }

  return { total: total, unique: unique, byColor: byColor, byType: byType, curve: curve };
}

/** Render grouped deck cards into `container` using GameRenderer's card thumbnail. */
export function renderDeckGrid(container, groups, ctx) {
  container.innerHTML = "";
  groups.forEach(function (group) {
    var colEl = document.createElement("div");
    colEl.className = "deck-col";

    var headerEl = document.createElement("div");
    headerEl.className = "deck-col-header";
    var labelEl = document.createElement("span");
    labelEl.className = "deck-col-label";
    labelEl.textContent = group.label;
    var countEl = document.createElement("span");
    countEl.className = "deck-col-count";
    countEl.textContent = String(
      group.cards.reduce(function (sum, c) { return sum + c.count; }, 0),
    );
    headerEl.appendChild(labelEl);
    headerEl.appendChild(countEl);

    var stackEl = document.createElement("div");
    stackEl.className = "deck-col-stack";

    group.cards.forEach(function (card) {
      var meta = (ctx.cardData && ctx.cardData[card.name]) || null;
      var thumb = ctx.renderer.makeCardThumbnail(card.name, meta, ctx.cardImages, false, ctx.previewEls);
      if (card.count > 1) {
        var wrap = document.createElement("div");
        wrap.className = "deck-col-card-wrap";
        wrap.appendChild(thumb);
        var badge = document.createElement("span");
        badge.className = "card-count-badge";
        badge.textContent = "×" + card.count;
        wrap.appendChild(badge);
        stackEl.appendChild(wrap);
      } else {
        stackEl.appendChild(thumb);
      }
    });

    colEl.appendChild(headerEl);
    colEl.appendChild(stackEl);
    container.appendChild(colEl);
  });
}
