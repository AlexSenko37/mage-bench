/**
 * Loads the hand-written commentary files.
 *
 * Kept apart from commentary.ts so the client bundle can import the parsing helpers
 * without pulling every commentary file in with them: this module is build-time only.
 */

import type { Commentary } from './commentary';

const COMMENTARY_FILES = import.meta.glob<Commentary>('../data/commentary/*.json', {
  eager: true,
  import: 'default',
});

/** The commentary for a game, or null when nobody has written one. */
export function loadCommentary(gameId: string): Commentary | null {
  const entry = Object.entries(COMMENTARY_FILES).find(([path]) => {
    const file = path.split('/').pop() ?? '';
    return file.replace(/\.json$/, '') === gameId;
  });
  return entry ? entry[1] : null;
}
