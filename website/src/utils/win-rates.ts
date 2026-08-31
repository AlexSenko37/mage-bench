// Per-model win rate, computed directly from exported games rather than the Python
// leaderboard: that pipeline buckets games into a fixed set of formats
// (src/magebench/leaderboard/formats.py) and a deck_type outside that set contributes
// nothing to it -- which silently excludes some formats used by this fork's own games.

import type { GameEntry } from './load-games';

export interface ModelWinRate {
  model: string;
  displayName: string;
  seats: number;
  wins: number;
  losses: number;
  winRate: number;
  selfPlaySeats: number;
  efforts: string[];
}

export interface WinRateSummary {
  rows: ModelWinRate[];
  totalGames: number;
  decidedGames: number;
  undecidedGames: number;
}

function displayName(model: string): string {
  const slash = model.lastIndexOf('/');
  return slash === -1 ? model : model.slice(slash + 1);
}

/**
 * Compute per-model win/loss counts and win rate from a list of games.
 *
 * Counting rules:
 * - Per SEAT, not per game: every seat with a model contributes one win or one loss.
 *   Self-play (the same model on both sides of a game) therefore counts as 1W + 1L for
 *   that model, i.e. 50% -- the correct expectation, not an artifact to special-case away.
 * - A game with no winner (a draw, a crash, an unfinished match) is skipped for win/loss
 *   purposes and counted as undecided; it is never treated as a loss for every player.
 * - Seats with no model (cpu/human opponents) are excluded entirely.
 * - A win is `placement === 1` when placement is present, else `name === game.winner` --
 *   this also makes the function correct for >2 player games later.
 * - Rows are keyed by model alone (not model+effort), so a handful of games at different
 *   reasoning efforts don't fragment into separate rows with tiny samples; the efforts
 *   actually seen are still tracked per row.
 */
export function computeModelWinRates(games: GameEntry[]): WinRateSummary {
  const byModel = new Map<string, { seats: number; wins: number; losses: number; selfPlaySeats: number; efforts: Set<string> }>();
  let decidedGames = 0;
  let undecidedGames = 0;

  for (const game of games) {
    const players = game.players || [];
    const modeledSeats = players.filter((p) => p.model);

    if (game.winner == null && !modeledSeats.some((p) => p.placement === 1)) {
      undecidedGames += 1;
      continue;
    }
    decidedGames += 1;

    const modelCounts = new Map<string, number>();
    for (const player of modeledSeats) {
      modelCounts.set(player.model as string, (modelCounts.get(player.model as string) || 0) + 1);
    }

    for (const player of modeledSeats) {
      const model = player.model as string;
      const isWin = player.placement != null ? player.placement === 1 : player.name === game.winner;
      const isSelfPlay = (modelCounts.get(model) || 0) > 1;

      let entry = byModel.get(model);
      if (!entry) {
        entry = { seats: 0, wins: 0, losses: 0, selfPlaySeats: 0, efforts: new Set() };
        byModel.set(model, entry);
      }
      entry.seats += 1;
      if (isWin) entry.wins += 1;
      else entry.losses += 1;
      if (isSelfPlay) entry.selfPlaySeats += 1;
      if (player.reasoning_effort) entry.efforts.add(player.reasoning_effort);
    }
  }

  const rows: ModelWinRate[] = Array.from(byModel.entries()).map(([model, stats]) => ({
    model,
    displayName: displayName(model),
    seats: stats.seats,
    wins: stats.wins,
    losses: stats.losses,
    winRate: stats.seats > 0 ? stats.wins / stats.seats : 0,
    selfPlaySeats: stats.selfPlaySeats,
    efforts: Array.from(stats.efforts).sort(),
  }));

  rows.sort((a, b) => {
    if (b.winRate !== a.winRate) return b.winRate - a.winRate;
    if (b.seats !== a.seats) return b.seats - a.seats;
    return a.displayName.localeCompare(b.displayName);
  });

  return { rows, totalGames: games.length, decidedGames, undecidedGames };
}
