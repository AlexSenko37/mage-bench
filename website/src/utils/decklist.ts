// Parses raw .dck-style decklist lines, e.g. "1 [TLA:146] Lightning Strike".
//
// Mirrors the Python DECKLIST_RE in src/magebench/game/export_card_data.py -- keep the two
// in sync if the line format ever changes.

export interface DecklistEntry {
  count: number;
  name: string;
  set?: string;
  num?: string;
  sideboard: boolean;
}

const LINE_RE = /^(?:(SB:)\s*)?(\d+)\s+\[(\w+):(\w+)\]\s+(.+)$/;

/**
 * Parse one raw decklist line. Returns null for non-card lines (e.g. the "NAME:..."
 * header some decklists start with) rather than rendering them as a phantom card.
 */
export function parseDecklistLine(line: string): DecklistEntry | null {
  const match = line.match(LINE_RE);
  if (match == null) {
    return null;
  }
  const [, sb, count, set, num, name] = match;
  return {
    count: Number.parseInt(count, 10),
    name,
    set,
    num,
    sideboard: sb != null,
  };
}
