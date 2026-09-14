import { modelShortName } from "./player-label.js";

/**
 * Pure logic for the draft replay: shaping the exported draft record into what the view
 * renders, with no DOM access so it can be tested directly.
 *
 * The export (src/magebench/game/export_draft.py) already threads each physical booster
 * across its trips round the pod and marks which cards wheeled. This module only slices
 * that by seat, orders it, and answers the per-card questions the view asks while drawing
 * a pack.
 */

/**
 * Draft seat name -> the same label the rest of the page uses for that player.
 *
 * A draft seat records the model it used but not the effort, so on its own it renders as
 * "GptOSS" while the replay's own panels say "GptOSS-medium" -- the same player under two
 * names on one page. The game's player list has both, so seats are matched to players by
 * model to borrow the fuller label. Seats sharing a model (self-play) are matched in
 * order, which is also the order the two lists are built in.
 *
 * Falls back to the model's short name when a seat has no counterpart in the game, which
 * is the case for a draft attached to a game it did not produce.
 */
export function draftSeatLabels(draft, players, labelByName) {
  var labels = {};
  var unclaimed = (players || []).slice();
  draftSeats(draft).forEach(function (seat) {
    var at = -1;
    for (var i = 0; i < unclaimed.length; i++) {
      if (unclaimed[i].model && unclaimed[i].model === seat.model) {
        at = i;
        break;
      }
    }
    if (at === -1) {
      labels[seat.seat] = seat.model ? modelShortName(seat.model) : seat.seat;
      return;
    }
    var player = unclaimed.splice(at, 1)[0];
    labels[seat.seat] =
      (labelByName && labelByName[player.name]) || modelShortName(seat.model);
  });
  return labels;
}

/** Seats that actually made picks, in export order. */
export function draftSeats(draft) {
  if (!draft || !draft.seats) return [];
  return draft.seats.filter(function (seat) {
    return seat.picks > 0;
  });
}

/** One seat's picks, ordered as they were made. */
export function picksForSeat(draft, seatName) {
  if (!draft || !draft.picks) return [];
  return draft.picks
    .filter(function (pick) {
      return pick.seat === seatName;
    })
    .sort(function (a, b) {
      return a.pick_number - b.pick_number;
    });
}

/** "P2p5" — booster round 2, fifth pick of that round. */
export function pickLabel(pick, picksPerRound) {
  var perRound = picksPerRound || 14;
  var inRound = ((pick.pick_number - 1) % perRound) + 1;
  return "P" + pick.round + "p" + inRound;
}

/**
 * How each card in the pack should be marked.
 *
 * "picked" is the card taken. "wheeled" is a card this seat saw in this same booster on
 * an earlier pass and passed — the table declined it too, which is the signal a drafter
 * is supposed to read. Everything else is "passed": seen once, not taken.
 */
export function classifyPackCard(pick, index) {
  if (pick.picked_index === index) return "picked";
  var name = pick.pack_cards[index];
  if (pick.wheeled && pick.wheeled.indexOf(name) !== -1) return "wheeled";
  return "passed";
}

/** Cards this seat passed on an earlier trip and could still take now. */
export function wheeledCount(pick) {
  return pick.wheeled ? pick.wheeled.length : 0;
}

/**
 * Running totals for one seat, for the summary strip.
 *
 * `reasoningPicks` matters because a model at low effort can return no reasoning tokens
 * at all: an empty reasoning panel is then correct rather than a capture failure, and the
 * count is what tells the two apart.
 */
export function summarizeSeat(draft, seatName) {
  var picks = picksForSeat(draft, seatName);
  var seat = (draft.seats || []).find(function (s) {
    return s.seat === seatName;
  });
  var withReasoning = picks.filter(function (p) {
    return p.reasoning && p.reasoning.trim().length > 0;
  });
  var withExplanation = picks.filter(function (p) {
    return p.explanation && p.explanation.trim().length > 0;
  });
  var elapsed = picks
    .map(function (p) {
      return typeof p.elapsed_secs === "number" ? p.elapsed_secs : null;
    })
    .filter(function (v) {
      return v !== null;
    });
  var wheels = picks.filter(function (p) {
    return wheeledCount(p) > 0;
  });
  return {
    seat: seatName,
    model: seat ? seat.model : null,
    picks: picks.length,
    fallbacks: seat ? seat.fallbacks : 0,
    costUsd: seat ? seat.cost_usd : 0,
    reasoningPicks: withReasoning.length,
    explanationPicks: withExplanation.length,
    wheelPicks: wheels.length,
    medianElapsed: median(elapsed),
  };
}

/**
 * The distinct prompts used in this draft, in the order the stages occur.
 *
 * Deliberately not per pick: every pick shares one prompt that differs only in the pool
 * and the pack, and the replay already draws both as cards. Repeating the instructions
 * above all 39 picks would bury the model's actual answer.
 */
export const STAGE_ORDER = ["pick", "spells", "spells_review", "lands"];

export function draftPrompts(draft) {
  if (!draft || !draft.prompts) return [];
  return draft.prompts.slice().sort(function (a, b) {
    var ai = STAGE_ORDER.indexOf(a.stage);
    var bi = STAGE_ORDER.indexOf(b.stage);
    // An unrecognised stage sorts last rather than first, so a new one added later shows
    // up at the end instead of silently displacing the pick prompt.
    return (ai === -1 ? STAGE_ORDER.length : ai) - (bi === -1 ? STAGE_ORDER.length : bi);
  });
}

/** Human label for a draft stage. */
export function stageLabel(stage) {
  var LABELS = {
    pick: "Each pick",
    spells: "Deckbuild: choosing spells",
    spells_review: "Deckbuild: reviewing the proposal",
    lands: "Deckbuild: choosing lands",
    deckbuild_fallback: "Deckbuild fell back to the heuristic",
  };
  return LABELS[stage] || stage;
}

/** Marker stage written when the heuristic builder produced the deck instead of the model. */
export const DECKBUILD_FALLBACK_STAGE = "deckbuild_fallback";

/**
 * Why the heuristic built this seat's deck, or null if the model's answer was used.
 *
 * Worth surfacing prominently: the picks can be entirely the model's while the 40 cards
 * that reach the table are RateCard's, and nothing about the decklist gives that away.
 */
export function deckbuildFallbackReason(draft, seatName) {
  const step = deckbuildForSeat(draft, seatName).find(function (s) {
    return s.stage === DECKBUILD_FALLBACK_STAGE;
  });
  return step ? step.content : null;
}

/** Deckbuild steps for one seat, in the order they were made. */
export function deckbuildForSeat(draft, seatName) {
  if (!draft || !draft.deckbuild) return [];
  return draft.deckbuild.filter(function (step) {
    return step.seat === seatName;
  });
}

/**
 * Pull the model's own prose out of a deckbuild reply.
 *
 * The reply is a JSON object whose `analysis` field is written for a human; showing the
 * raw JSON instead buries it. A reply that will not parse is shown verbatim rather than
 * dropped, since an unparseable deckbuild answer is itself worth seeing.
 */
export function deckbuildAnalysis(step) {
  if (!step.content) return "";
  try {
    var parsed = JSON.parse(step.content);
    if (parsed && typeof parsed.analysis === "string") return parsed.analysis;
  } catch {
    return step.content;
  }
  return step.content;
}

function median(values) {
  if (!values.length) return null;
  var sorted = values.slice().sort(function (a, b) {
    return a - b;
  });
  var mid = Math.floor(sorted.length / 2);
  return sorted.length % 2 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2;
}
