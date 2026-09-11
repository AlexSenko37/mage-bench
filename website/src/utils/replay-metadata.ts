import type { GameExportV9 } from '../types/game-export';
import { buildPlayerLabelMap } from '../scripts/player-label.js';

export interface ReplayBlunderCounts {
  questionable: number;
  minor: number;
  moderate: number;
  major: number;
}

export interface ReplayBlunderSummary {
  total: number;
  counts: ReplayBlunderCounts;
}

export function buildReplayTitle(players: GameExportV9['players']): string {
  // Seat names (PilotA/PilotB) are opaque by design during play so a model cannot tell
  // which opponent it faces. The replay is under no such constraint, and a title
  // reading PilotA vs PilotB makes the reader look the mapping up every time.
  const labelByName = buildPlayerLabelMap(players);
  return players
    .map((player) => {
      const label = labelByName[player.name] || player.name;
      const deck_name = player.deck_name || player.commander || '';
      return deck_name ? `${label} (${deck_name})` : label;
    })
    .join(' vs ');
}

export function summarizeReplayBlunders(
  annotations: GameExportV9['annotations'],
): ReplayBlunderSummary | null {
  const counts: ReplayBlunderCounts = {
    questionable: 0,
    minor: 0,
    moderate: 0,
    major: 0,
  };

  let total = 0;
  for (const annotation of annotations) {
    if (annotation.type !== 'blunder') {
      continue;
    }
    counts[annotation.severity] += 1;
    total += 1;
  }

  if (total === 0) {
    return null;
  }

  return { total, counts };
}

export function formatReplayBlunderSummary(summary: ReplayBlunderSummary): string {
  const parts: string[] = [];

  if (summary.counts.major > 0) {
    parts.push(`${summary.counts.major} major`);
  }
  if (summary.counts.moderate > 0) {
    parts.push(`${summary.counts.moderate} moderate`);
  }
  if (summary.counts.minor > 0) {
    parts.push(`${summary.counts.minor} minor`);
  }
  if (summary.counts.questionable > 0) {
    parts.push(`${summary.counts.questionable} questionable`);
  }

  const suffix = summary.total === 1 ? ' blunder' : ' blunders';
  return `${parts.join(', ')}${suffix}`;
}

export function normalizedBlunderScriptVersion(blunder_script_version: number | null | undefined): number {
  return blunder_script_version || 1;
}
