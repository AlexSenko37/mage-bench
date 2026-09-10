/**
 * Pure logic for the draft replay: shaping the exported draft record into what the view
 * renders, with no DOM access so it can be tested directly.
 *
 * The export (src/magebench/game/export_draft.py) already threads each physical booster
 * across its trips round the pod and marks which cards wheeled. This module only slices
 * that by seat, orders it, and answers the per-card questions the view asks while drawing
 * a pack.
 */

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
    wheelPicks: wheels.length,
    medianElapsed: median(elapsed),
  };
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
