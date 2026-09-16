/**
 * The `?view=` parameter that makes a game page's tab linkable.
 *
 * A link to the commentary (or decks, or draft) has to survive being pasted somewhere, so
 * the active tab lives in the URL the same way the replay's snapshot lives in `?s=`.
 * Pure string helpers, so the controller keeps the DOM work and this stays testable.
 */

export const DEFAULT_VIEW = 'replay';

/**
 * The view a URL asks for, or "replay".
 *
 * Falls back for anything the page cannot honour — a missing or unknown value, or a tab
 * this game does not have (a link to the commentary of a game nobody has written one for)
 * — so a shared link always lands on something.
 */
export function parseViewParam(search: string, available: readonly string[]): string {
  const requested = new URLSearchParams(search).get('view');
  if (!requested || requested === DEFAULT_VIEW) return DEFAULT_VIEW;
  return available.includes(requested) ? requested : DEFAULT_VIEW;
}

/**
 * `search` with `view` set, or removed when it names the default view.
 *
 * Other parameters are left alone: the snapshot in `?s=` and the audit decision in `?d=`
 * have to survive a tab switch, so that a link carries both the tab and the position.
 */
export function applyViewParam(search: string, view: string): string {
  const params = new URLSearchParams(search);
  if (!view || view === DEFAULT_VIEW) {
    params.delete('view');
  } else {
    params.set('view', view);
  }
  const next = params.toString();
  return next ? `?${next}` : '';
}
