/**
 * Hand-written commentary for a game, and the markup it is written in.
 *
 * Commentary lives in `src/data/commentary/<game id>.json` and is rendered at build time,
 * so a game without a file simply has no Commentary tab. Two inline markers are supported:
 *
 *   [[card:Momo, Playful Pet]]          a card, shown with the replay's hover preview
 *   [[card:Momo, Playful Pet|Momo]]     ... with different link text
 *   [[player:PilotA|Fable]]             a jump to that seat's turn in this round
 *   [[view:draft|here]]                 a link that opens another tab on this page
 *
 * Card names must match the export's `card_data` / `card_images` keys, which is what the
 * preview looks cards up by.
 */

export interface CommentarySegment {
  kind: 'text' | 'card' | 'player' | 'view';
  /** Text to display. */
  text: string;
  /** Card name, for `card` segments. */
  card?: string;
  /** Seat name (e.g. "PilotA"), for `player` segments. */
  seat?: string;
  /** Tab to open (e.g. "draft"), for `view` segments. */
  view?: string;
}

/** A titled block above the turn-by-turn notes, e.g. "The Models" or "Draft". */
export interface CommentarySection {
  label: string;
  paragraphs: string[];
}

export interface CommentaryRound {
  /** Heading, e.g. "Turn 1". */
  label: string;
  /** Seat name -> the game turn that seat played in this round. */
  gameTurns: Record<string, number>;
  paragraphs: string[];
}

export interface Commentary {
  gameId: string;
  /** Seat name -> the name the commentary calls that player, e.g. PilotA -> "Fable". */
  players?: Record<string, string>;
  intro?: string;
  /** Titled blocks shown above the opening hands and the turn-by-turn notes. */
  sections?: CommentarySection[];
  rounds: CommentaryRound[];
}

const MARKER = /\[\[(card|player|view):([^\]|]+)(?:\|([^\]]*))?\]\]/g;

/**
 * Split commentary text into plain text and marker segments.
 *
 * Unknown or malformed markers are left as literal text rather than dropped, so a typo
 * shows up in the page instead of quietly deleting a sentence.
 */
export function parseCommentaryText(text: string): CommentarySegment[] {
  const segments: CommentarySegment[] = [];
  let lastIndex = 0;
  MARKER.lastIndex = 0;

  let match = MARKER.exec(text);
  while (match !== null) {
    if (match.index > lastIndex) {
      segments.push({ kind: 'text', text: text.slice(lastIndex, match.index) });
    }
    const [, kind, target, label] = match;
    const display = (label ?? target).trim();
    if (kind === 'card') {
      segments.push({ kind: 'card', text: display, card: target.trim() });
    } else if (kind === 'view') {
      segments.push({ kind: 'view', text: display, view: target.trim() });
    } else {
      segments.push({ kind: 'player', text: display, seat: target.trim() });
    }
    lastIndex = match.index + match[0].length;
    match = MARKER.exec(text);
  }

  if (lastIndex < text.length) {
    segments.push({ kind: 'text', text: text.slice(lastIndex) });
  }
  return segments;
}

/** Every card named in a commentary, in first-mention order. */
export function commentaryCardNames(commentary: Commentary): string[] {
  const seen: string[] = [];
  commentary.rounds.forEach((round) => {
    round.paragraphs.forEach((paragraph) => {
      parseCommentaryText(paragraph).forEach((segment) => {
        if (segment.kind === 'card' && segment.card && !seen.includes(segment.card)) {
          seen.push(segment.card);
        }
      });
    });
  });
  return seen;
}

/**
 * The first snapshot of a seat's turn, for the jump links.
 *
 * Prefers the snapshot where that seat is the active player, so a turn that opens on the
 * other seat's response still lands on the right side, and falls back to the first
 * snapshot of the turn.
 */
export function findTurnSnapshotIndex(
  snapshots: Array<{ turn?: number; active_player?: string | null }> | null | undefined,
  turn: number,
  seat?: string | null,
): number | null {
  const list = snapshots ?? [];
  if (seat) {
    const seated = list.findIndex((snap) => snap.turn === turn && snap.active_player === seat);
    if (seated !== -1) return seated;
  }
  const anyTurn = list.findIndex((snap) => snap.turn === turn);
  return anyTurn === -1 ? null : anyTurn;
}
