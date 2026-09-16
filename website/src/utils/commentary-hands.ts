/**
 * Opening hands and per-turn draws, derived from the game's own snapshots.
 *
 * Deriving beats writing them into the commentary by hand: the cards are already in the
 * export, and a hand-written list would be one more thing to get wrong.
 */

export interface HandCard {
  name?: string;
}

export interface SnapshotPlayer {
  name?: string;
  hand?: Array<HandCard | string>;
  battlefield?: Array<HandCard | string>;
}

export interface HandSnapshot {
  // Nullable rather than merely optional: that is how the export's own snapshot type
  // declares these, and this has to accept a game export as it comes.
  turn?: number | null;
  step?: string | null;
  active_player?: string | null;
  players?: SnapshotPlayer[];
}

function namesIn(cards: Array<HandCard | string> | undefined): string[] {
  return (cards ?? [])
    .map((card) => (typeof card === 'string' ? card : card?.name))
    .filter((name): name is string => typeof name === 'string' && name.length > 0);
}

function playerIn(snapshot: HandSnapshot | undefined, seat: string): SnapshotPlayer | undefined {
  return (snapshot?.players ?? []).find((p) => p?.name === seat);
}

function handOf(snapshot: HandSnapshot | undefined, seat: string): string[] {
  return namesIn(playerIn(snapshot, seat)?.hand);
}

function battlefieldOf(snapshot: HandSnapshot | undefined, seat: string): string[] {
  return namesIn(playerIn(snapshot, seat)?.battlefield);
}

/** Cards in `cards` that `baseline` does not account for, counting duplicates. */
function added(cards: string[], baseline: string[]): string[] {
  const remaining = new Map<string, number>();
  baseline.forEach((name) => remaining.set(name, (remaining.get(name) ?? 0) + 1));
  const result: string[] = [];
  cards.forEach((name) => {
    const left = remaining.get(name) ?? 0;
    if (left > 0) {
      remaining.set(name, left - 1);
      return;
    }
    result.push(name);
  });
  return result;
}

/**
 * Each seat's opening hand, taken from the first snapshot where every seat holds cards.
 *
 * That snapshot is after mulligans, which is the hand the game is actually played from.
 */
export function openingHands(
  snapshots: HandSnapshot[] | null | undefined,
  seats: readonly string[],
): Record<string, string[]> {
  const list = snapshots ?? [];
  const index = list.findIndex((snapshot) => seats.every((seat) => handOf(snapshot, seat).length > 0));
  if (index === -1) return {};
  const hands: Record<string, string[]> = {};
  seats.forEach((seat) => {
    hands[seat] = handOf(list[index], seat);
  });
  return hands;
}

/**
 * What a seat gained in hand on one of its turns: the draw, in practice.
 *
 * Measured at that turn's first main phase against the end of the seat's previous turn,
 * so a card cast in between doesn't hide the draw. On a seat's first turn there is no
 * previous turn, so it is measured against the opening hand: the player on the draw does
 * draw on its first turn, and only the player on the play skips one.
 */
export function drawnCards(
  snapshots: HandSnapshot[] | null | undefined,
  turn: number,
  seat: string,
): string[] {
  const list = snapshots ?? [];
  let mainIndex = list.findIndex((s) => s.turn === turn && s.step === 'PRECOMBAT_MAIN');
  if (mainIndex === -1) mainIndex = list.findIndex((s) => s.turn === turn);
  if (mainIndex === -1) return [];

  const previousTurn = turn - 2;
  let baselineIndex = -1;
  for (let i = 0; i < list.length; i += 1) {
    if (list[i].turn === previousTurn) baselineIndex = i;
  }
  if (baselineIndex === -1) {
    // A seat's first turn. Fall back to its opening hand: the player on the draw draws on
    // its first turn, and only the player on the play skips one, which this comparison
    // gives for free (its hand is unchanged). Returning nothing here instead hid the
    // second player's opening draw.
    baselineIndex = list.findIndex((snapshot) => handOf(snapshot, seat).length > 0);
    if (baselineIndex === -1 || baselineIndex > mainIndex) return [];
  }

  const gained = added(handOf(list[mainIndex], seat), handOf(list[baselineIndex], seat));

  // A permanent bounced back to hand also "appears" in hand, but it was not drawn: the
  // Warden that Astra's Submersible returned showed up as a second draw alongside the real
  // one. Drop anything that was on this seat's battlefield at the baseline and has since
  // left it.
  const wasInPlay = battlefieldOf(list[baselineIndex], seat);
  const stillInPlay = battlefieldOf(list[mainIndex], seat);
  return gained.filter((name) => !(wasInPlay.includes(name) && !stillInPlay.includes(name)));
}

/**
 * A small image for a card, from the export's baked images.
 *
 * Those are baked at `version=normal`, which is far more than a thumbnail needs, so this
 * asks for the small one; a card with no baked image falls back to Scryfall by name.
 */
export function thumbnailUrl(cardImages: Record<string, string> | null | undefined, name: string): string {
  const baked = cardImages ? cardImages[name] : undefined;
  if (baked) return baked.replace(/version=(small|normal|large|png)/, 'version=small');
  return `https://api.scryfall.com/cards/named?exact=${encodeURIComponent(name)}&format=image&version=small`;
}
